package io.split.android.client.lifecycle;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.arch.lifecycle.ProcessLifecycleOwner;

import io.split.android.client.TrackClient;
import io.split.android.client.cache.IMySegmentsCache;
import io.split.android.client.cache.ISplitCache;
import io.split.android.client.impressions.ImpressionsManager;
import io.split.android.engine.experiments.RefreshableSplitFetcherProvider;
import io.split.android.engine.segments.RefreshableMySegmentsFetcherProvider;

public class LifecycleManager implements LifecycleObserver {

    ImpressionsManager mImpressionsManager;
    TrackClient mTrackClient;
    RefreshableSplitFetcherProvider mSplitFetcherProvider;
    RefreshableMySegmentsFetcherProvider mMySegmentsFetcherProvider;
    IMySegmentsCache mMySegmentsCache;
    ISplitCache mSplitCache;

    public LifecycleManager(ImpressionsManager impressionsManager,
                            TrackClient trackClient,
                            RefreshableSplitFetcherProvider splitFetcherProvider,
                            RefreshableMySegmentsFetcherProvider mySegmentsFetcherProvider,
                            ISplitCache splitCache,
                            IMySegmentsCache mySegmentsCache) {

        mImpressionsManager = impressionsManager;
        mTrackClient = trackClient;
        mSplitFetcherProvider = splitFetcherProvider;
        mMySegmentsFetcherProvider = mySegmentsFetcherProvider;
        mSplitCache = splitCache;
        mMySegmentsCache = mySegmentsCache;

        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private void onPause() {
        if(mImpressionsManager != null) {
            mImpressionsManager.pause();
            mImpressionsManager.saveToDisk();
        }

        if(mTrackClient != null) {
            mTrackClient.pause();
            mTrackClient.saveToDisk();
        }

        if(mSplitFetcherProvider != null) {
            mSplitFetcherProvider.pause();
        }

        if(mMySegmentsFetcherProvider != null) {
            mMySegmentsFetcherProvider.pause();
        }

        if(mSplitCache != null) {
            mSplitCache.saveToDisk();
        }

        if(mMySegmentsCache != null) {
            mMySegmentsCache.saveToDisk();
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private void onResume() {
        if(mImpressionsManager != null) {
            mImpressionsManager.resume();
        }

        if(mTrackClient != null) {
            mTrackClient.resume();
        }

        if(mSplitFetcherProvider != null) {
            mSplitFetcherProvider.resume();
        }

        if(mMySegmentsFetcherProvider != null) {
            mMySegmentsFetcherProvider.resume();
        }
    }

    public void destroy() {
        ProcessLifecycleOwner.get().getLifecycle().removeObserver(this);
    }

}