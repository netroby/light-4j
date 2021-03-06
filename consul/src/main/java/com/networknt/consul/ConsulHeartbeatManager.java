package com.networknt.consul;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.networknt.consul.client.ConsulClient;
import com.networknt.utility.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * consul heart beat manager. passing status service id is registered here，
 * and this class will set passing status for serviceId（in fact it is corresponding checkId of serviceId),
 * then the heart beat process is done.
 *
 * Switcher is used to enable heart beat or disable heart beat.
 * 
 * @author zhanglei
 *
 */
public class ConsulHeartbeatManager {
	private static final Logger logger = LoggerFactory.getLogger(ConsulHeartbeatManager.class);
	private ConsulClient client;
	// all serviceIds that need heart beats.
	private ConcurrentHashSet<String> serviceIds = new ConcurrentHashSet<String>();

	private ThreadPoolExecutor jobExecutor;
	private ScheduledExecutorService heartbeatExecutor;
	// last heart beat switcher status
	private boolean lastHeartBeatSwitcherStatus = false;
	private volatile boolean currentHeartBeatSwitcherStatus = false;
	// switcher check times
	private int switcherCheckTimes = 0;

	public ConsulHeartbeatManager(ConsulClient client) {
		this.client = client;
		heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
		ArrayBlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<Runnable>(
				10000);
		jobExecutor = new ThreadPoolExecutor(5, 30, 30 * 1000,
				TimeUnit.MILLISECONDS, workQueue);
	}

	public void start() {
		heartbeatExecutor.scheduleAtFixedRate(
				new Runnable() {
					@Override
					public void run() {
						// Because consul check set pass triggers consul
						// server write operation，frequently heart beat will impact consul
						// performance，so heart beat takes long cycle and switcher check takes short cycle.
						// multiple check on switcher and then send one heart beat to consul server.
						// TODO change to switcher listener approach.
						try {
							boolean switcherStatus = isHeartbeatOpen();
							if (isSwitcherChange(switcherStatus)) { // heart beat switcher status changed
								processHeartbeat(switcherStatus);
							} else {// heart beat switcher status not changed.
								if (switcherStatus) {// switcher is on, check MAX_SWITCHER_CHECK_TIMES and then send a heart beat
									switcherCheckTimes++;
									if (switcherCheckTimes >= ConsulConstants.MAX_SWITCHER_CHECK_TIMES) {
										processHeartbeat(true);
										switcherCheckTimes = 0;
									}
								}
							}
						} catch (Exception e) {
							logger.error("consul heartbeat executor err:",
									e);
						}
					}
				}, ConsulConstants.SWITCHER_CHECK_CIRCLE,
				ConsulConstants.SWITCHER_CHECK_CIRCLE, TimeUnit.MILLISECONDS);
	}

	/**
	 * check heart beat switcher status, if switcher is changed, then change lastHeartBeatSwitcherStatus
	 * to the latest status.
	 * 
	 * @param switcherStatus
	 * @return
	 */
	private boolean isSwitcherChange(boolean switcherStatus) {
		boolean ret = false;
		if (switcherStatus != lastHeartBeatSwitcherStatus) {
			ret = true;
			lastHeartBeatSwitcherStatus = switcherStatus;
			logger.info("heartbeat switcher change to " + switcherStatus);
		}
		return ret;
	}

	protected void processHeartbeat(boolean isPass) {
		for (String serviceId : serviceIds) {
			try {
				jobExecutor.execute(new HeartbeatJob(serviceId, isPass));
			} catch (RejectedExecutionException ree) {
				logger.error("execute heartbeat job fail! serviceId:"
						+ serviceId + " is rejected");
			}
		}
	}

	public void close() {
		heartbeatExecutor.shutdown();
		jobExecutor.shutdown();
		logger.info("Consul heartbeatManager closed.");
	}

	/**
	 * Add consul serviceId，added serviceId will set passing status to keep sending heart beat.
	 * 
	 * @param serviceId service Id
	 */
	public void addHeartbeatServcieId(String serviceId) {
		serviceIds.add(serviceId);
	}

	/**
	 * remove serviceId，corresponding serviceId won't send heart beat
	 * 
	 * @param serviceId service Id
	 */
	public void removeHeartbeatServiceId(String serviceId) {
		serviceIds.remove(serviceId);
	}

	// check if heart beat switcher is on
	private boolean isHeartbeatOpen() {
		return currentHeartBeatSwitcherStatus;
	}

	public void setHeartbeatOpen(boolean open) {
		currentHeartBeatSwitcherStatus = open;
	}

	class HeartbeatJob implements Runnable {
		private String serviceId;
		private boolean isPass;

		public HeartbeatJob(String serviceId, boolean isPass) {
			super();
			this.serviceId = serviceId;
			this.isPass = isPass;
		}

		@Override
		public void run() {
			try {
				if (isPass) {
					client.checkPass(serviceId);
				} else {
					client.checkFail(serviceId);
				}
			} catch (Exception e) {
				logger.error(
						"consul heartbeat-set check pass error!serviceId:"
								+ serviceId, e);
			}

		}

	}

	public void setClient(ConsulClient client) {
		this.client = client;
	}


}
