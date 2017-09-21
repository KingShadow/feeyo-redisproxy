package com.feeyo.redis.net.front.handler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feeyo.redis.engine.RedisEngineCtx;
import com.feeyo.redis.engine.codec.RedisPipelineResponseDecoder;
import com.feeyo.redis.engine.codec.RedisPipelineResponseDecoder.PipelineResponse;
import com.feeyo.redis.engine.codec.RedisRequest;
import com.feeyo.redis.engine.manage.stat.StatUtil;
import com.feeyo.redis.net.backend.RedisBackendConnection;
import com.feeyo.redis.net.backend.callback.DirectTransTofrontCallBack;
import com.feeyo.redis.net.front.RedisFrontConnection;
import com.feeyo.redis.net.front.route.RouteResult;
import com.feeyo.redis.net.front.route.RouteResultNode;
import com.feeyo.redis.nio.util.TimeUtil;
import com.feeyo.redis.virtualmemory.AppendMessageResult;
import com.feeyo.redis.virtualmemory.Message;
import com.feeyo.redis.virtualmemory.PutMessageResult;

public class PipelineCommandHandler extends AbstractPipelineCommandHandler {
	
	private static Logger LOGGER = LoggerFactory.getLogger(PipelineCommandHandler.class);
	

	public PipelineCommandHandler(RedisFrontConnection frontCon) {
		super(frontCon);
	}
	
	@Override
	protected void commonHandle(RouteResult rrs) throws IOException {
		
		super.commonHandle(rrs);
		
    	// 写出
		for (RouteResultNode rrn : rrs.getRouteResultNodes()) {
			ByteBuffer buffer =  getRequestBufferByRRN(rrn);
			RedisBackendConnection backendConn = writeToBackend(rrn.getPhysicalNode(), buffer, new PipelineDirectTransTofrontCallBack());
			if ( backendConn != null )
				this.addBackendConnection( backendConn );
		}
		
		// 埋点
		frontCon.getSession().setRequestTimeMills(TimeUtil.currentTimeMillis());
		frontCon.getSession().setRequestCmd( PIPELINE_CMD );
		frontCon.getSession().setRequestKey( PIPELINE_KEY );
		frontCon.getSession().setRequestSize( rrs.getRequestSize() );
	}
	
	// 
	private class PipelineDirectTransTofrontCallBack extends DirectTransTofrontCallBack {

		private RedisPipelineResponseDecoder decoder = new RedisPipelineResponseDecoder();
		
		@Override
		public void handleResponse(RedisBackendConnection backendCon, byte[] byteBuff) throws IOException {

			// 解析此次返回的数据条数
			PipelineResponse pipelineResponse = decoder.parse( byteBuff );
			if ( !pipelineResponse.isOK() )
				return;
			
			// 这里缓存进文件的是 pipelienDecoder中缓存的数据。 防止断包之后丢数据
			String address = backendCon.getPhysicalNode().getName();
			ResponseStatusCode state = recvResponse(address, pipelineResponse.getCount(), pipelineResponse.getResps());
			
			// 如果所有请求，应答都已经返回
			if ( state == ResponseStatusCode.ALL_NODE_COMPLETED ) {
				
				// 获取所有应答
				List<Object> resps = mergeResponses();
				if (resps != null) {

					try {
						String password = frontCon.getPassword();
						int requestSize = frontCon.getSession().getRequestSize();
						long requestTimeMills = frontCon.getSession().getRequestTimeMills();
						long responseTimeMills = TimeUtil.currentTimeMillis();
						int responseSize = 0;

						for (Object resp : resps) {
							if (resp instanceof PutMessageResult) {
								PutMessageResult pmr = (PutMessageResult) resp;
								AppendMessageResult amr = pmr.getAppendMessageResult();
								Message msg = RedisEngineCtx.INSTANCE().getVirtualMemoryService().getMessage( amr.getWroteOffset(), amr.getWroteBytes() );
								// 通知该消息已经被消费
								RedisEngineCtx.INSTANCE().getVirtualMemoryService().markAsConsumed(amr.getWroteOffset(), amr.getWroteBytes());
								
								responseSize += this.writeToFront(frontCon, msg.getBody(), 0);
							} else if (resp instanceof byte[]) {
								responseSize += this.writeToFront(frontCon, (byte[])resp, 0);
							}
						}

						// resps.clear(); // help GC
						resps = null;

						// 后段链接释放
						removeAndReleaseBackendConnection(backendCon);

						// 数据收集
						StatUtil.collect(password, PIPELINE_CMD, PIPELINE_KEY, requestSize, responseSize,
								(int) (responseTimeMills - requestTimeMills), false);

						// child 收集
						for (RedisRequest req : rrs.getRequests()) {
							String childCmd = new String( req.getArgs()[0] );
							StatUtil.collect(password, childCmd, PIPELINE_KEY, requestSize, responseSize,
									(int) (responseTimeMills - requestTimeMills), true);
						}
						
					} catch (IOException e2) {

						if (frontCon != null) {
							frontCon.close("write err");
						}

						// 由 reactor close
						LOGGER.error("backend write to front err:", e2);
						throw e2;
						
					} finally {
						// 释放锁
						frontCon.releaseLock();;
					}
				}
			} else if ( state == ResponseStatusCode.THE_NODE_COMPLETED  ) {
				
				// 如果此后端节点数据已经返回完毕，则释放链接
				removeAndReleaseBackendConnection(backendCon);
				
			} else if ( state == ResponseStatusCode.ERROR ) {
				// 添加回复到虚拟内存中出错。
				responseAppendError();
			}
		}
	}
}
