package com.github.kfcfans.oms.worker.core.tracker.processor;

import akka.actor.ActorSelection;
import com.github.kfcfans.oms.worker.OhMyWorker;
import com.github.kfcfans.common.RemoteConstant;
import com.github.kfcfans.oms.worker.common.constants.TaskStatus;
import com.github.kfcfans.oms.worker.common.utils.AkkaUtils;
import com.github.kfcfans.oms.worker.core.executor.ProcessorRunnable;
import com.github.kfcfans.oms.worker.persistence.TaskDO;
import com.github.kfcfans.oms.worker.pojo.model.InstanceInfo;
import com.github.kfcfans.oms.worker.pojo.request.ProcessorReportTaskStatusReq;
import com.github.kfcfans.oms.worker.pojo.request.ProcessorTrackerStatusReportReq;
import com.github.kfcfans.oms.worker.pojo.request.TaskTrackerStartTaskReq;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

/**
 * 负责管理 Processor 的执行
 *
 * @author tjq
 * @since 2020/3/20
 */
@Slf4j
public class ProcessorTracker {

    // 记录创建时间
    private long startTime;
    // 任务实例信息
    private InstanceInfo instanceInfo;
    // 冗余 instanceId，方便日志
    private String instanceId;

    private String taskTrackerAddress;
    private ActorSelection taskTrackerActorRef;

    private ThreadPoolExecutor threadPool;

    private static final int THREAD_POOL_QUEUE_MAX_SIZE = 100;

    /**
     * 创建 ProcessorTracker（其实就是创建了个执行用的线程池 T_T）
     */
    public ProcessorTracker(TaskTrackerStartTaskReq request) {

        // 赋值
        this.startTime = System.currentTimeMillis();
        this.instanceInfo = request.getInstanceInfo();
        this.instanceId = request.getInstanceInfo().getInstanceId();
        this.taskTrackerAddress = request.getTaskTrackerAddress();
        String akkaRemotePath = AkkaUtils.getAkkaRemotePath(taskTrackerAddress, RemoteConstant.Task_TRACKER_ACTOR_NAME);
        this.taskTrackerActorRef = OhMyWorker.actorSystem.actorSelection(akkaRemotePath);

        // 初始化
        initProcessorPool();
        initTimingJob();
    }

    /**
     * 提交任务到线程池执行
     * 1.0版本：TaskTracker有任务就dispatch，导致 ProcessorTracker 本地可能堆积过多的任务，造成内存压力。为此 ProcessorTracker 在线程
     *         池队列堆积到一定程度时，会将数据持久化到DB，然后通过异步线程定时从数据库中取回任务，重新提交执行。
     *         联动：数据库的SPID设计、TaskStatus段落设计等，全部取消...
     *         last commitId: 341953aceceafec0fbe7c3d9a3e26451656b945e
     * 2.0版本：ProcessorTracker定时向TaskTracker发送心跳消息，心跳消息中包含了当前线程池队列任务个数，TaskTracker根据ProcessorTracker
     *         的状态判断能否继续派发任务。因此，ProcessorTracker本地不会堆积过多任务，故删除 持久化机制 ╥﹏╥...！
     * @param newTask 需要提交到线程池执行的任务
     */
    public void submitTask(TaskDO newTask) {

        boolean success = false;
        // 1. 设置值并提交执行
        newTask.setJobId(instanceInfo.getJobId());
        newTask.setInstanceId(instanceInfo.getInstanceId());
        newTask.setAddress(taskTrackerAddress);

        ProcessorRunnable processorRunnable = new ProcessorRunnable(instanceInfo, taskTrackerActorRef, newTask);
        try {
            threadPool.submit(processorRunnable);
            success = true;
        }catch (RejectedExecutionException ignore) {
            log.warn("[ProcessorTracker-{}] submit task(taskId={},taskName={}) to ThreadPool failed due to ThreadPool has too much task waiting to process, this task will dispatch to other ProcessorTracker.",
                    instanceId, newTask.getTaskId(), newTask.getTaskName());
        }catch (Exception e) {
            log.error("[ProcessorTracker-{}] submit task(taskId={},taskName={}) to ThreadPool failed.", instanceId, newTask.getTaskId(), newTask.getTaskName(), e);
        }

        // 2. 回复接收成功
        if (success) {
            ProcessorReportTaskStatusReq reportReq = new ProcessorReportTaskStatusReq();
            reportReq.setInstanceId(instanceId);
            reportReq.setTaskId(newTask.getTaskId());
            reportReq.setStatus(TaskStatus.WORKER_RECEIVED.getValue());

            reportReq.setStatus(TaskStatus.WORKER_RECEIVED.getValue());
            taskTrackerActorRef.tell(reportReq, null);

            log.debug("[ProcessorTracker-{}] submit task(taskId={}, taskName={}) success, current queue size: {}.",
                    instanceId, newTask.getTaskId(), newTask.getTaskName(), threadPool.getQueue().size());
        }
    }

    /**
     * 任务是否超时
     */
    public boolean isTimeout() {
        return System.currentTimeMillis() - startTime > instanceInfo.getInstanceTimeoutMS();
    }


    /**
     * 初始化线程池
     */
    private void initProcessorPool() {

        int poolSize = instanceInfo.getThreadConcurrency();
        // 待执行队列，为了防止对内存造成较大压力，内存队列不能太大
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(THREAD_POOL_QUEUE_MAX_SIZE);
        // 自定义线程池中线程名称
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("oms-processor-pool-%d").build();
        // 拒绝策略：直接抛出异常
        RejectedExecutionHandler rejectionHandler = new ThreadPoolExecutor.AbortPolicy();

        threadPool = new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS, queue, threadFactory, rejectionHandler);

        // 当没有任务执行时，允许销毁核心线程（即线程池最终存活线程个数可能为0）
        threadPool.allowCoreThreadTimeOut(true);
    }

    /**
     * 初始化定时任务
     */
    private void initTimingJob() {
        ThreadFactory threadFactory = new ThreadFactoryBuilder().setNameFormat("oms-processor-timing-pool-%d").build();
        ScheduledExecutorService timingPool = Executors.newSingleThreadScheduledExecutor(threadFactory);

        timingPool.scheduleAtFixedRate(new TimingStatusReportRunnable(), 0, 10, TimeUnit.SECONDS);
    }


    /**
     * 定时向 TaskTracker 汇报（携带任务执行信息的心跳）
     */
    private class TimingStatusReportRunnable implements Runnable {

        @Override
        public void run() {

            // 1. 查询数据库中等待执行的任务数量
            long waitingNum = threadPool.getQueue().size();

            // 2. 发送请求
            ProcessorTrackerStatusReportReq req = new ProcessorTrackerStatusReportReq(instanceInfo.getInstanceId(), waitingNum);
            taskTrackerActorRef.tell(req, null);
        }
    }

}
