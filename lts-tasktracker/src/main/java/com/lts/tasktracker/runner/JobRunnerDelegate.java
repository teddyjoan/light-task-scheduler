package com.lts.tasktracker.runner;

import com.lts.core.domain.Action;
import com.lts.core.domain.JobWrapper;
import com.lts.core.logger.Logger;
import com.lts.core.logger.LoggerFactory;
import com.lts.core.support.LoggerName;
import com.lts.core.support.SystemClock;
import com.lts.tasktracker.Result;
import com.lts.tasktracker.domain.Response;
import com.lts.tasktracker.domain.TaskTrackerApplication;
import com.lts.tasktracker.logger.BizLoggerFactory;
import com.lts.tasktracker.logger.BizLoggerImpl;
import com.lts.tasktracker.monitor.TaskTrackerMonitor;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Job Runner 的代理类,
 * 1. 做一些错误处理之类的
 * 2. 监控统计
 * 3. Context信息设置
 * @author Robert HG (254963746@qq.com) on 8/16/14.
 */
public class JobRunnerDelegate implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggerName.TaskTracker);
    private JobWrapper jobWrapper;
    private RunnerCallback callback;
    private BizLoggerImpl logger;
    private TaskTrackerApplication application;
    private TaskTrackerMonitor monitor;

    public JobRunnerDelegate(TaskTrackerApplication application,
                             JobWrapper jobWrapper, RunnerCallback callback) {
        this.jobWrapper = jobWrapper;
        this.callback = callback;
        this.application = application;
        this.logger = (BizLoggerImpl) BizLoggerFactory.getLogger(
                application.getBizLogLevel(),
                application.getRemotingClient(), application);
        monitor = (TaskTrackerMonitor) application.getMonitor();
    }

    @Override
    public void run() {
        try {
            LtsLoggerFactory.setLogger(logger);

            while (jobWrapper != null) {
                long startTime = SystemClock.now();
                // 设置当前context中的jobId
                logger.setId(jobWrapper.getJobId(), jobWrapper.getJob().getTaskId());
                Response response = new Response();
                response.setJobWrapper(jobWrapper);
                try {
                    application.getRunnerPool().getRunningJobManager()
                            .in(jobWrapper.getJobId());
                    Result result = application.getRunnerPool().getRunnerFactory()
                            .newRunner().run(jobWrapper.getJob());
                    if (result == null) {
                        response.setAction(Action.EXECUTE_SUCCESS);
                    } else {
                        if (result.getAction() == null) {
                            response.setAction(Action.EXECUTE_SUCCESS);
                        }else{
                            response.setAction(result.getAction());
                        }
                        response.setMsg(result.getMsg());
                    }
                    long time = SystemClock.now() - startTime;
                    monitor.addRunningTime(time);
                    LOGGER.info("Job execute completed : {}, time:{} ms.", jobWrapper, time);
                } catch (Throwable t) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    response.setAction(Action.EXECUTE_EXCEPTION);
                    response.setMsg(sw.toString());
                    long time = SystemClock.now() - startTime;
                    monitor.addRunningTime(time);
                    LOGGER.info("Job execute error : {}, time: {}, {}",
                            jobWrapper, time, t.getMessage(), t);
                } finally {
                    logger.removeId();
                    application.getRunnerPool().getRunningJobManager()
                            .out(jobWrapper.getJobId());
                }
                // 统计数据
                monitor(response.getAction());

                jobWrapper = callback.runComplete(response);
            }
        } finally {
            LtsLoggerFactory.remove();
        }
    }

    private void monitor(Action action) {
        if (action == null) {
            return;
        }
        switch (action) {
            case EXECUTE_SUCCESS:
                monitor.incSuccessNum();
                break;
            case EXECUTE_FAILED:
                monitor.incFailedNum();
                break;
            case EXECUTE_LATER:
                monitor.incExeLaterNum();
                break;
            case EXECUTE_EXCEPTION:
                monitor.incExeExceptionNum();
                break;
        }
    }

}
