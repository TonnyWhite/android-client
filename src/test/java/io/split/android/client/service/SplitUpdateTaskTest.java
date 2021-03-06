package io.split.android.client.service;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import io.split.android.client.dtos.SplitChange;
import io.split.android.client.service.http.HttpFetcher;
import io.split.android.client.service.http.HttpFetcherException;
import io.split.android.client.service.splits.SplitChangeProcessor;
import io.split.android.client.service.splits.SplitsSyncTask;
import io.split.android.client.service.splits.SplitsUpdateTask;
import io.split.android.client.storage.splits.ProcessedSplitChange;
import io.split.android.client.storage.splits.SplitsStorage;
import io.split.android.helpers.FileHelper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SplitUpdateTaskTest {

    HttpFetcher<SplitChange> mSplitsFetcher;
    SplitsStorage mSplitsStorage;
    SplitChange mSplitChange = null;
    SplitChangeProcessor mSplitChangeProcessor;

    SplitsUpdateTask mTask;

    Map<String, Object> mDefaultParams = new HashMap<>();


    @Before
    public void setup() {
        mDefaultParams.clear();
        mDefaultParams.put("since", -1L);
        mSplitsFetcher = (HttpFetcher<SplitChange>) Mockito.mock(HttpFetcher.class);
        mSplitsStorage = Mockito.mock(SplitsStorage.class);
        mSplitChangeProcessor = Mockito.spy(SplitChangeProcessor.class);
        mTask = new SplitsUpdateTask(mSplitsFetcher, mSplitsStorage,
                mSplitChangeProcessor, 100L);
        loadSplitChanges();
    }

    @Test
    public void correctExecution() throws HttpFetcherException {
        when(mSplitsStorage.getTill()).thenReturn(-1L);
        when(mSplitsFetcher.execute(mDefaultParams)).thenReturn(mSplitChange);

        mTask.execute();

        verify(mSplitsFetcher, times(1)).execute(mDefaultParams);
        verify(mSplitsStorage, times(1)).update(any());
        verify(mSplitChangeProcessor, times(1)).process(mSplitChange);
    }

    @Test
    public void storedChangeNumBigger() throws HttpFetcherException {
        long changeNum = 234567833L;
        Map<String, Object> params = new HashMap<>();
        params.put("since", changeNum);

        when(mSplitsStorage.getTill()).thenReturn(changeNum);
        when(mSplitsFetcher.execute(params)).thenReturn(mSplitChange);

        mTask.execute();

        verify(mSplitsFetcher, never()).execute(params);
        verify(mSplitsStorage, never()).update(any());
        verify(mSplitChangeProcessor, never()).process(mSplitChange);
    }

    @Test
    public void fetcherException() throws HttpFetcherException {
        mTask = new SplitsUpdateTask(mSplitsFetcher, mSplitsStorage,
                mSplitChangeProcessor, 1L);
        when(mSplitsStorage.getTill()).thenReturn(-1L);
        when(mSplitsFetcher.execute(mDefaultParams))
                .thenThrow(HttpFetcherException.class)
                .thenThrow(HttpFetcherException.class)
                .thenThrow(HttpFetcherException.class)
                .thenReturn(mSplitChange);

        mTask.execute();

        verify(mSplitsFetcher, times(4)).execute(mDefaultParams);
        verify(mSplitsStorage, times(1)).update(any());
        verify(mSplitChangeProcessor, times(1)).process(mSplitChange);
    }

    @Test
    public void storageException() throws HttpFetcherException {
        when(mSplitsStorage.getTill()).thenReturn(-1L);
        when(mSplitsFetcher.execute(mDefaultParams)).thenReturn(mSplitChange);
        doThrow(NullPointerException.class).when(mSplitsStorage).update(any(ProcessedSplitChange.class));

        mTask.execute();

        verify(mSplitsFetcher, times(1)).execute(mDefaultParams);
        verify(mSplitsStorage, times(1)).update(any());
        verify(mSplitChangeProcessor, times(1)).process(mSplitChange);
    }

    @After
    public void tearDown() {
        reset(mSplitsFetcher);
        reset(mSplitsStorage);
    }

    private void loadSplitChanges() {
        if (mSplitChange == null) {
            FileHelper fileHelper = new FileHelper();
            mSplitChange = fileHelper.loadSplitChangeFromFile("split_changes_1.json");
        }
    }
}
