package com.birbit.android.jobqueue;

import com.birbit.android.jobqueue.messaging.MessageFactory;
import com.birbit.android.jobqueue.messaging.MessageQueue;
import com.birbit.android.jobqueue.messaging.PriorityMessageQueue;
import com.birbit.android.jobqueue.messaging.message.AddJobMessage;
import com.birbit.android.jobqueue.messaging.message.CancelMessage;
import com.birbit.android.jobqueue.messaging.message.CommandMessage;
import com.birbit.android.jobqueue.messaging.message.PublicQueryMessage;
import com.path.android.jobqueue.AsyncAddCallback;
import com.path.android.jobqueue.CancelResult;
import com.path.android.jobqueue.Job;
import com.path.android.jobqueue.JobStatus;
import com.path.android.jobqueue.TagConstraint;
import com.path.android.jobqueue.callback.JobManagerCallback;
import com.path.android.jobqueue.callback.JobManagerCallbackAdapter;
import com.path.android.jobqueue.config.Configuration;
import com.path.android.jobqueue.log.JqLog;

import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class JobManager2 {
    final JobManagerThread jobManagerThread;
    private final PriorityMessageQueue messageQueue;
    private final MessageFactory messageFactory;
    private Thread chefThread;

    /**
     * Creates a JobManager with the given configuration
     *
     * @param configuration The configuration to be used for the JobManager
     *
     * @see com.path.android.jobqueue.config.Configuration.Builder
     */
    public JobManager2(Configuration configuration) {
        messageFactory = new MessageFactory();
        messageQueue = new PriorityMessageQueue(configuration.timer(), messageFactory);
        jobManagerThread = new JobManagerThread(configuration, messageQueue, messageFactory);
        chefThread = new Thread(jobManagerThread, "job-manager");
        chefThread.start();
    }

    /**
     * Starts the JobManager if it is not already running.
     *
     * @see #stop()
     */
    public void start() {
        PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.START, null);
        messageQueue.post(message);
    }

    /**
     * Stops the JobManager. Currently running Jobs will continue to run but no new Jobs will be
     * run until restarted.
     *
     * @see #start()
     */
    public void stop() {
        PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.STOP, null);
        messageQueue.post(message);
    }

    /**
     * Returns the number of consumer threads that are currently running Jobs. This number includes
     * consumer threads that are currently idle.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     * @return The number of consumer threads
     */
    public int getActiveConsumerCount() {
        assertNotInMainThread();
        PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.ACTIVE_CONSUMER_COUNT, null);
        return new PublicQueryFuture(messageQueue, message).getSafe();
    }

    /**
     * Destroys the JobManager. You cannot make any calls to this JobManager after this call.
     * Useful to be called after your tests.
     *
     * @see #stopAndWaitUntilConsumersAreFinished()
     */
    public void destroy() {
        JqLog.d("destroying job queue");
        stopAndWaitUntilConsumersAreFinished();
        CommandMessage message = messageFactory.obtain(CommandMessage.class);
        message.set(CommandMessage.QUIT);
        messageQueue.post(message);
        jobManagerThread.callbackManager.destroy();
    }

    /**
     * Stops the JobManager and waits until all currently running Jobs are complete (or failed).
     * Useful to be called in your tests.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     *
     * @see #destroy()
     */
    public void stopAndWaitUntilConsumersAreFinished() {
        waitUntilConsumersAreFinished(true);
    }

    /**
     * Waits until all consumers are destroyed. If min consumer count is NOT 0, this method will
     * never return.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     */
    public void waitUntilConsumersAreFinished() {
        waitUntilConsumersAreFinished(false);
    }

    private void waitUntilConsumersAreFinished(boolean stop) {
        assertNotInMainThread();
        final CountDownLatch latch = new CountDownLatch(1);
        jobManagerThread.consumerManager.addNoConsumersListener(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
                jobManagerThread.consumerManager.removeNoConsumersListener(this);
            }
        });
        if (stop) {
            stop();
        }
        if(jobManagerThread.consumerManager.getWorkerCount() == 0) {
            return;
        }
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        PublicQueryMessage pm = messageFactory.obtain(PublicQueryMessage.class);
        pm.set(PublicQueryMessage.CLEAR, null);
        new PublicQueryFuture(jobManagerThread.callbackManager.messageQueue, pm).getSafe();
    }

    /**
     * Adds a Job to the JobManager. This method instantly returns and does not wait until the Job
     * is added. You should always prefer this method over {@link #addJob(Job)}.
     *
     * @param job The Job to be added
     *
     * @see #addJobInBackground(Job, AsyncAddCallback)
     * @see #addJob(Job)
     */
    public void addJobInBackground(Job job) {
        AddJobMessage message = messageFactory.obtain(AddJobMessage.class);
        message.setJob(job);
        messageQueue.post(message);
    }

    /**
     * Cancels the Jobs that match the given criteria. If a Job that matches the criteria is
     * currently running, JobManager waits until it finishes its {@link Job#onRun()} method before
     * calling the callback.
     *
     * @param cancelCallback The callback to call once cancel is handled
     * @param constraint The constraint to be used to match tags
     * @param tags The list of tags
     */
    public void cancelJobsInBackground(final CancelResult.AsyncCancelCallback cancelCallback,
            final TagConstraint constraint, final String... tags) {
        if (constraint == null) {
            throw new IllegalArgumentException("must provide a TagConstraint");
        }
        CancelMessage message = messageFactory.obtain(CancelMessage.class);
        message.setCallback(cancelCallback);
        message.setConstraint(constraint);
        message.setTags(tags);
        messageQueue.post(message);
    }

    /**
     * Adds a JobManagerCallback to observe this JobManager.
     *
     * @param callback The callback to be added
     */
    public void addCallback(JobManagerCallback callback) {
        jobManagerThread.addCallback(callback);
    }

    /**
     * Removes the JobManagerCallback from the callbacks list. This method is safe to be called
     * inside any method of the JobManagerCallback.
     *
     * @param callback The callback to be removed
     *
     * @return true if the callback is removed, false otherwise (if it did not exist).
     */
    public boolean removeCallback(JobManagerCallback callback) {
        return jobManagerThread.removeCallback(callback);
    }

    /**
     * Adds the Job to the JobManager and waits until the add is handled.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     *
     * Even if you are not on the main thread, you should prefer using
     * {@link #addJobInBackground(Job)} or {@link #addJobInBackground(Job, AsyncAddCallback)} if
     * you don't need to block your thread until the Job is actually added.
     *
     * @param job The Job to be added
     *
     * @see #addJobInBackground(Job)
     * @see #addJobInBackground(Job, AsyncAddCallback)
     */
    public void addJob(Job job) {
        assertNotInMainThread("Cannot call this method on main thread. Use addJobInBackground "
                + "instead.");
        final CountDownLatch latch = new CountDownLatch(1);
        final String uuid = job.getId();
        addCallback(new JobManagerCallbackAdapter() {
            @Override
            public void onJobAdded(Job job) {
                if (uuid.equals(job.getId())) {
                    latch.countDown();
                    removeCallback(this);
                }
            }
        });
        addJobInBackground(job);
        try {
            latch.await();
        } catch (InterruptedException ignored) {

        }
    }

    /**
     * Adds a Job in a background thread and calls the provided callback once the Job is added
     * to the JobManager.
     *
     * @param job The Job to be added
     * @param callback The callback to be invoked once Job is saved in the JobManager's queues
     */
    public void addJobInBackground(Job job, final AsyncAddCallback callback) {
        if (callback == null) {
            addJobInBackground(job);
            return;
        }
        final String uuid = job.getId();
        addCallback(new JobManagerCallbackAdapter() {
            @Override
            public void onJobAdded(Job job) {
                if (uuid.equals(job.getId())) {
                    try {
                        callback.onAdded();
                    } finally {
                        removeCallback(this);
                    }
                }
            }
        });
        addJobInBackground(job);
    }

    /**
     * Cancels jobs that match the given criteria. This method blocks until the cancellation is
     * handled, which might be a long time if a Job that matches the given criteria is currently
     * running. Consider using
     * {@link #cancelJobsInBackground(CancelResult.AsyncCancelCallback, TagConstraint, String...)}
     * if possible.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     *
     * @param constraint The constraints to be used for tags
     * @param tags The list of tags
     *
     * @return A cancel result that has the list of cancelled and failed to cancel Jobs. A job
     * might fail to cancel if it already started before cancel request is handled.
     */
    public CancelResult cancelJobs(TagConstraint constraint, String... tags) {
        assertNotInMainThread("Cannot call this method on main thread. Use cancelJobsInBackground"
                + " instead");
        if (constraint == null) {
            throw new IllegalArgumentException("must provide a TagConstraint");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final CancelResult[] result = new CancelResult[1];
        CancelResult.AsyncCancelCallback myCallback = new CancelResult.AsyncCancelCallback() {
            @Override
            public void onCancelled(CancelResult cancelResult) {
                result[0] = cancelResult;
                latch.countDown();
            }
        };
        CancelMessage message = messageFactory.obtain(CancelMessage.class);
        message.setConstraint(constraint);
        message.setTags(tags);
        message.setCallback(myCallback);
        messageQueue.post(message);
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    /**
     * Returns the number of jobs in the JobManager. This number does not include jobs that are
     * currently running.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     *
     * @return The number of jobs that are waiting to be run
     */
    public int count() {
        assertNotInMainThread();
        PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.COUNT, null);
        return new PublicQueryFuture(messageQueue, message).getSafe();
    }

    /**
     * Returns the number of jobs that are ready to be executed but waiting in the queue.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     * @return The number of jobs that are ready to be executed but waiting in the queue.
     */
    public int countReadyJobs() {
        assertNotInMainThread();
        PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.COUNT_READY, null);
        return new PublicQueryFuture(messageQueue, message).getSafe();
    }

    /**
     * Returns the current status of a given job
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     * @param id The id of the job ({@link Job#getId()})
     *
     * @return The current status of the Job
     */
    public JobStatus getJobStatus(String id) {
        PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.JOB_STATUS, id, null);
        Integer status = new PublicQueryFuture(messageQueue, message).getSafe();
        return JobStatus.values()[status];
    }

    /**
     * Clears all waiting Jobs in the JobManager. Note that this won't touch any job that is
     * currently running.
     * <p>
     * You cannot call this method on the main thread because it may potentially block it for a long
     * time.
     */
    public void clear() {
        final PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.CLEAR, null);
        new PublicQueryFuture(messageQueue, message).getSafe();
    }

    void internalRunInJobManagerThread(final Runnable runnable) throws Throwable {
        final Throwable[] error = new Throwable[1];
        final PublicQueryMessage message = messageFactory.obtain(PublicQueryMessage.class);
        message.set(PublicQueryMessage.INTERNAL_RUNNABLE, null);
        new PublicQueryFuture(messageQueue, message) {
            @Override
            public void onResult(int result) { // this is hacky but allright
                try {
                    runnable.run();
                } catch (Throwable t) {
                    error[0] = t;
                }
                super.onResult(result);
            }
        }.getSafe();
        if (error[0] != null) {
            throw error[0];
        }
    }

    private void assertNotInMainThread() {
        assertNotInMainThread("Cannot call this method on main thread.");
    }
    private void assertNotInMainThread(String message) {
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            throw new IllegalStateException(message);
        }
    }

    static class PublicQueryFuture implements Future<Integer>,IntCallback {
        final MessageQueue messageQueue;
        volatile Integer result = null;
        final CountDownLatch latch = new CountDownLatch(1);
        final PublicQueryMessage message;

        public PublicQueryFuture(MessageQueue messageQueue, PublicQueryMessage message) {
            this.messageQueue = messageQueue;
            this.message = message;
            message.setCallback(this);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return latch.getCount() == 0;
        }

        public Integer getSafe() {
            try {
                return get();
            } catch (Throwable t) {
                JqLog.e(t, "message is not complete");
            }
            throw new RuntimeException("cannot get the result of the JobManager query");
        }

        @Override
        public Integer get() throws InterruptedException, ExecutionException {
            messageQueue.post(message);
            latch.await();
            return result;
        }

        @Override
        public Integer get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            messageQueue.post(message);
            latch.await(timeout, unit);
            return result;
        }

        @Override
        public void onResult(int result) {
            this.result = result;
            latch.countDown();
        }
    }
}