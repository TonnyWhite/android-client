package io.split.android.client.service.mysegments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;

import io.split.android.client.dtos.MySegment;
import io.split.android.client.service.HttpFetcher;
import io.split.android.client.service.HttpFetcherException;
import io.split.android.client.service.executor.SplitTask;
import io.split.android.client.storage.mysegments.MySegmentsStorage;
import io.split.android.client.utils.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

public class MySegmentsSyncTask implements SplitTask {

    private final HttpFetcher<List<MySegment>> mMySegmentsFetcher;
    private final MySegmentsStorage mMySegmentsStorage;

    public MySegmentsSyncTask(HttpFetcher<List<MySegment>> mySegmentsFetcher, MySegmentsStorage mySegmentsStorage) {
        checkNotNull(mySegmentsFetcher);
        checkNotNull(mySegmentsStorage);

        mMySegmentsFetcher = mySegmentsFetcher;
        mMySegmentsStorage = mySegmentsStorage;
    }

    @Override
    public void execute() {
        try {
            List<MySegment> mySegments = mMySegmentsFetcher.execute(new HashMap<>());
            mMySegmentsStorage.set(getNameList(mySegments));
        } catch (HttpFetcherException e) {
            logError("Exception when fetching my segments: " + e.getLocalizedMessage());
        } catch (Exception e) {
            logError("Unknown error while retrieving my segments: " + e.getLocalizedMessage());
        }
    }

    private void logError(String message) {
        Logger.e("Error while executing my segments sync task: " + message);
    }

    private List<String> getNameList(List<MySegment> mySegments) {
        List<String> nameList = new ArrayList<String>();
        for(MySegment segment : mySegments) {
            nameList.add(segment.name);
        }
        return nameList;
    }
}
