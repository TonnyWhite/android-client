package io.split.android.client.service.synchronizer;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

import io.split.android.client.SplitClientConfig;
import io.split.android.client.dtos.Event;
import io.split.android.client.impressions.Impression;
import io.split.android.client.service.sseclient.PushNotificationManager;
import io.split.android.client.service.sseclient.feedbackchannel.PushManagerEventBroadcaster;
import io.split.android.client.service.sseclient.feedbackchannel.BroadcastedEventListener;
import io.split.android.client.service.sseclient.feedbackchannel.PushStatusEvent;
import io.split.android.client.service.sseclient.reactor.MySegmentsUpdateWorker;
import io.split.android.client.service.sseclient.reactor.SplitUpdatesWorker;
import io.split.android.client.utils.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

public class SyncManagerImpl implements SyncManager, BroadcastedEventListener {

    private final SplitClientConfig mSplitClientConfig;
    private final PushManagerEventBroadcaster mPushManagerEventBroadcaster;
    private final Synchronizer mSynchronizer;
    private final PushNotificationManager mPushNotificationManager;
    private SplitUpdatesWorker mSplitUpdateWorker;
    private MySegmentsUpdateWorker mMySegmentUpdateWorker;


    private AtomicBoolean isPollingEnabled;

    public SyncManagerImpl(@NonNull SplitClientConfig splitClientConfig,
                           @NonNull Synchronizer synchronizer,
                           @NonNull PushNotificationManager pushNotificationManager,
                           @NonNull SplitUpdatesWorker splitUpdateWorker,
                           @NonNull MySegmentsUpdateWorker mySegmentUpdateWorker,
                           @NonNull PushManagerEventBroadcaster pushManagerEventBroadcaster) {

        mSynchronizer = checkNotNull(synchronizer);
        mSplitClientConfig = checkNotNull(splitClientConfig);
        mPushNotificationManager = checkNotNull(pushNotificationManager);
        mSplitUpdateWorker = checkNotNull(splitUpdateWorker);
        mMySegmentUpdateWorker = checkNotNull(mySegmentUpdateWorker);
        mPushManagerEventBroadcaster = checkNotNull(pushManagerEventBroadcaster);

        isPollingEnabled = new AtomicBoolean(false);
    }


    @Override
    public void start() {

        mSynchronizer.loadSplitsFromCache();
        mSynchronizer.loadMySegmentsFromCache();

        isPollingEnabled.set(!mSplitClientConfig.streamingEnabled());
        if (mSplitClientConfig.streamingEnabled()) {
            mSynchronizer.synchronizeSplits();
            mSynchronizer.syncronizeMySegments();
            mPushManagerEventBroadcaster.register(this);
            mSplitUpdateWorker.start();
            mMySegmentUpdateWorker.start();
            mPushNotificationManager.start();

        } else {
            mSynchronizer.startPeriodicFetching();
        }
        mSynchronizer.startPeriodicRecording();
    }

    @Override
    public void pause() {
        mSynchronizer.pause();
    }

    @Override
    public void resume() {
        mSynchronizer.resume();
    }

    @Override
    public void flush() {
        mSynchronizer.flush();
    }

    @Override
    public void pushEvent(Event event) {
        mSynchronizer.pushEvent(event);
    }

    @Override
    public void pushImpression(Impression impression) {
        mSynchronizer.pushImpression(impression);
    }

    @Override
    public void stop() {
        mSynchronizer.stopPeriodicFetching();
        mSynchronizer.stopPeriodicRecording();
        mSynchronizer.destroy();
        mPushNotificationManager.stop();
        mSplitUpdateWorker.stop();
        mMySegmentUpdateWorker.stop();
    }

    @Override
    public void onEvent(PushStatusEvent message) {
        switch (message.getMessage()) {
            case ENABLE_POLLING:
                Logger.d("Disable polling event message received.");
                if (!isPollingEnabled.get()) {
                    isPollingEnabled.set(true);
                    mSynchronizer.startPeriodicFetching();
                    Logger.i("Polling enabled.");
                }
                break;
            case DISABLE_POLLING:
                Logger.d("Disable polling event message received.");
                mSynchronizer.stopPeriodicFetching();
                isPollingEnabled.set(false);
                Logger.i("Polling disabled.");
                break;
            case STREAMING_CONNECTED:
                mSynchronizer.synchronizeSplits();
                mSynchronizer.syncronizeMySegments();
            default:
                Logger.e("Invalide SSE event received: " + message.getMessage());
        }
    }
}
