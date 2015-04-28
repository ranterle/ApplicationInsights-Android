package com.microsoft.applicationinsights.library;

import android.os.AsyncTask;

import com.microsoft.applicationinsights.contracts.shared.IJsonSerializable;
import com.microsoft.applicationinsights.library.config.IQueueConfig;
import com.microsoft.applicationinsights.logging.InternalLogging;

import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This singleton class sends data to the endpoint
 */
class ChannelQueue {

    /**
     * Logging tag for this class
     */
    private static final String TAG = "TelemetryQueue";

    /**
     * The synchronization LOCK for queueing items
     */
    private final Object LOCK = new Object();

    /**
     * The configuration for this queue
     */
    protected IQueueConfig config;

    /**
     * The timer for this queue
     */
    protected final Timer timer;

    /**
     * The linked list for this queue
     */
    protected final List<IJsonSerializable> list;

    /**
     * If true the app is crashing and data should be persisted instead of sent
     */
    protected volatile boolean isCrashing;

    /**
     * All tasks which have been scheduled and not cancelled
     */
    private TimerTask scheduledPersistenceTask;

    /**
     * Persistence used for saving queue items.
     */
    private Persistence persistence;

    /**
     * Prevent external instantiation
     */
    protected ChannelQueue(IQueueConfig config) {
        this.list = new LinkedList<IJsonSerializable>();
        this.timer = new Timer("Application Insights Sender Queue", true);
        this.config = config;
        this.isCrashing = false;
        this.persistence = Persistence.getInstance();
    }

    /**
     * Adds an item to the sender queue
     *
     * @param item a telemetry item to enqueue
     * @return true if the item was successfully added to the queue
     */
    protected boolean enqueue(IJsonSerializable item) {
        // prevent invalid argument exception
        if (item == null) {
            return false;
        }

        boolean success;
        synchronized (this.LOCK) {
            // attempt to add the item to the queue
            success = this.list.add(item);

            if (success) {
                if ((this.list.size() >= this.config.getMaxBatchCount()) || isCrashing) {
                    // persisting if the queue is full
                    flush();
                } else if (this.list.size() == 1) {
                    schedulePersitenceTask();
                }
            } else {
                InternalLogging.warn(TAG, "Unable to add item to queue");
            }
        }

        return success;
    }

    /**
     * Empties the queue and sends all items to persistence
     */
    protected void flush() {
        // cancel the scheduled persistence task if it exists
        if (this.scheduledPersistenceTask != null) {
            this.scheduledPersistenceTask.cancel();
        }

        IJsonSerializable[] data;
        synchronized (this.LOCK) {
            if (!list.isEmpty()) {
                data = new IJsonSerializable[list.size()];
                list.toArray(data);
                list.clear();

                executePersistenceTask(data);
            }
        }
    }

    /**
     * Schedules a persistence task based on max maxBatchIntervalMs.
     *
     * @see com.microsoft.applicationinsights.library.ChannelQueue.TriggerPersistTask
     */
    protected void schedulePersitenceTask(){
        // schedule a FlushTask if this is the first item in the queue
        this.scheduledPersistenceTask = new TriggerPersistTask();
        this.timer.schedule(this.scheduledPersistenceTask, this.config.getMaxBatchIntervalMs());
    }

    /**
     * Initiates persisting the content queue.
     *
     * @see com.microsoft.applicationinsights.library.ChannelQueue.PersistenceTask
     */
    protected void executePersistenceTask(IJsonSerializable[] data){
        PersistenceTask persistTask = new PersistenceTask(data);
        persistTask.execute();
    }

    /**
     * Set the isCrashing flag
     *
     * @param isCrashing if true the app is assumed to be crashing and data will be written to disk
     */
    protected void setIsCrashing(Boolean isCrashing) {
        this.isCrashing = isCrashing;
    }

    /**
     * Set the persistence instance used to save items.
     *
     * @param persistence the persitence instance which should be used
     */
    protected void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    /**
     * Set the config for this queue.
     *
     * @param config a config which contains information about how this queue should behave.
     */
    protected void setQueueConfig(IQueueConfig config){
        this.config = config;
    }

    /**
     * A task to initiate queue sendPendingData on another thread
     */
    private class TriggerPersistTask extends TimerTask {
        /**
         * The sender INSTANCE is provided to the constructor as a test hook
         *
         */
        public TriggerPersistTask() {}

        @Override
        public void run() {
            flush();
        }
    }

    /**
     * A task to initiate flushing the queue and persisting it's data
     */
    private class PersistenceTask extends AsyncTask<Void, Void, Void> {
        private IJsonSerializable[] data;
        public PersistenceTask(IJsonSerializable[] data) {
            this.data = data;
        }

        @Override
        protected Void doInBackground(Void... params) {
                if (this.data != null) {

                if (persistence != null) {
                    persistence.persist(this.data, false);
                }
            }

            return null;
        }
    }
}

