package io.split.android.client.service.sseclient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.List;
import java.util.Map;

import io.split.android.client.service.executor.SplitTask;
import io.split.android.client.service.executor.SplitTaskExecutionInfo;
import io.split.android.client.service.executor.SplitTaskExecutionListener;
import io.split.android.client.service.executor.SplitTaskExecutionStatus;
import io.split.android.client.service.executor.SplitTaskExecutor;
import io.split.android.client.service.executor.SplitTaskFactory;
import io.split.android.client.service.executor.SplitTaskType;
import io.split.android.client.service.sseclient.feedbackchannel.BroadcastedEvent;
import io.split.android.client.service.sseclient.feedbackchannel.BroadcastedEventType;
import io.split.android.client.service.sseclient.feedbackchannel.PushManagerEventBroadcaster;
import io.split.android.client.service.sseclient.notifications.NotificationProcessor;
import io.split.android.client.utils.Logger;

import static androidx.core.util.Preconditions.checkNotNull;
import static io.split.android.client.service.executor.SplitTaskType.GENERIC_TASK;
import static java.lang.reflect.Modifier.PRIVATE;

public class PushNotificationManager implements SplitTaskExecutionListener, SseClientListener {

    private final static String DATA_FIELD = "data";
    private final static int SSE_RECONNECT_TIME_IN_SECONDS = 70;
    private final static int INITIAL_CONNECTION_RETRY_IN_SECONDS = 1;
    private final static int RETRY_EXPONENTIAL_BASE = 2;

    private final SseClient mSseClient;
    private final SplitTaskExecutor mTaskExecutor;
    private final PushManagerEventBroadcaster mPushManagerEventBroadcaster;
    private final SplitTaskFactory mSplitTaskFactory;
    private final NotificationProcessor mNotificationProcessor;
    private long mNextRetryPeriod = INITIAL_CONNECTION_RETRY_IN_SECONDS;
    private String mSseDownNotificatorTaskId = null;
    private String mSseTokenCaducatedNotificatorTaskId = null;

    public PushNotificationManager(@NonNull SseClient sseClient,
                                   @NonNull SplitTaskExecutor taskExecutor,
                                   @NonNull SplitTaskFactory splitTaskFactory,
                                   @NonNull NotificationProcessor notificationProcessor,
                                   @NonNull PushManagerEventBroadcaster pushManagerEventBroadcaster) {

        mSseClient = checkNotNull(sseClient);
        mSplitTaskFactory = checkNotNull(splitTaskFactory);
        mTaskExecutor = checkNotNull(taskExecutor);
        mNotificationProcessor = checkNotNull(notificationProcessor);
        mPushManagerEventBroadcaster = checkNotNull(pushManagerEventBroadcaster);
        mSseClient.setListener(this);
    }

    public void start() {
        mTaskExecutor.submit(
                mSplitTaskFactory.createSseAuthenticationTask(),
                this);

        scheduleSseDownNotification();
    }

    public void stop() {
        mSseClient.disconnect();
    }

    private void connectToSse(String token, List<String> channels) {
        mSseClient.connect(token, channels);
    }

    private void scheduleConnection() {
        mTaskExecutor.schedule(
                mSplitTaskFactory.createSseAuthenticationTask(),
                getRetryPeriod(), this);
    }

    private synchronized long getRetryPeriod() {
        long currentValue = mNextRetryPeriod;
        mNextRetryPeriod = mNextRetryPeriod * RETRY_EXPONENTIAL_BASE;
        return currentValue;
    }

    private synchronized void resetRetryPeriod() {
        mNextRetryPeriod = INITIAL_CONNECTION_RETRY_IN_SECONDS;
    }

    private void scheduleSseDownNotification() {
        mSseDownNotificatorTaskId = mTaskExecutor.schedule(
                new SseDownNotificator(),
                SSE_RECONNECT_TIME_IN_SECONDS,
                null);
    }

    private void scheduleSseTokenCaducation(long expirationTime) {
        mSseTokenCaducatedNotificatorTaskId = mTaskExecutor.schedule(
                new SseTokenCaducationNotificator(),
                expirationTime - System.currentTimeMillis() / 1000,
                null);
    }

    private void notifyPushEnabled() {
        mPushManagerEventBroadcaster.pushMessage(new BroadcastedEvent(BroadcastedEventType.PUSH_ENABLED));
    }

    private void notifyPushDisabled() {
        mPushManagerEventBroadcaster.pushMessage(new BroadcastedEvent(BroadcastedEventType.PUSH_DISABLED));
    }

    //
//     SSE client listener implementation
//
    @Override
    public void onOpen() {
        resetRetryPeriod();
        notifyPushEnabled();
        scheduleSseDownNotification();
    }

    @Override
    public void onMessage(Map<String, String> values) {
        String messageData = values.get(DATA_FIELD);
        if (messageData != null) {
            mNotificationProcessor.process(messageData);
        }
        scheduleSseDownNotification();
    }

    @Override
    public void onKeepAlive() {
        scheduleSseDownNotification();
    }

    @Override
    public void onError() {
        scheduleConnection();
        cancelSseDownNotificator();
        notifyPushDisabled();
    }

    private void cancelSseDownNotificator() {
        if (mSseDownNotificatorTaskId != null) {
            mTaskExecutor.stopTask(mSseDownNotificatorTaskId);
        }
    }

    //
//      Split Task Executor Listener implementation
//
    @Override
    public void taskExecuted(@NonNull SplitTaskExecutionInfo taskInfo) {
        if (SplitTaskType.SSE_AUTHENTICATION_TASK.equals(taskInfo.getTaskType())) {
            SseJwtToken jwtToken = unpackResult(taskInfo);
            if (jwtToken != null && jwtToken.getChannels().size() > 0) {
                connectToSse(jwtToken.getRawJwt(), jwtToken.getChannels());
            } else {
                scheduleConnection();
                notifyPushDisabled();
            }
        }
    }

    @Nullable
    private SseJwtToken unpackResult(SplitTaskExecutionInfo taskInfo) {
        if (!SplitTaskExecutionStatus.SUCCESS.equals(taskInfo.getStatus())) {
            return null;
        }
        Boolean isStreamingEnabled = taskInfo.getBoolValue(SplitTaskExecutionInfo.IS_STREAMING_ENABLED);
        if (isStreamingEnabled != null && isStreamingEnabled.booleanValue()) {
            Object token = taskInfo.getObjectValue(SplitTaskExecutionInfo.PARSED_SSE_JWT);
            if (token != null) {
                try {
                    return (SseJwtToken) token;
                } catch (ClassCastException e) {
                    Logger.e("Sse authentication error. JWT not valid: " +
                            e.getLocalizedMessage());
                }
            } else {
                Logger.e("Sse authentication error. Token not available.");
            }
        } else {
            Logger.e("Couldn't connect to SSE server. Streaming is disabled.");
        }
        return null;
    }

    @VisibleForTesting(otherwise = PRIVATE)
    public class ScheduledAlarmTask implements SplitTask {

        private final int mAlarmType;

        public ScheduledAlarmTask(int alarmType) {
            this.mAlarmType = alarmType;
        }

        @NonNull
        @Override
        public SplitTaskExecutionInfo execute() {
            scheduleConnection();
            mPushManagerEventBroadcaster.pushMessage(new BroadcastedEvent(
                    BroadcastedEventType.PUSH_DISABLED));
            return SplitTaskExecutionInfo.success(GENERIC_TASK);
        }
    }
}
