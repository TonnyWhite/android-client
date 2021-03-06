package io.split.android.client.service.synchronizer;

import androidx.annotation.VisibleForTesting;

import io.split.android.client.dtos.Event;
import io.split.android.client.impressions.Impression;

@VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
public interface SyncManager {
    void start();

    void pause();

    void resume();

    void flush();

    void pushEvent(Event event);

    void pushImpression(Impression impression);

    void stop();

}
