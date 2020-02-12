package io.split.android.engine.experiments;

import com.google.common.collect.Lists;

import org.junit.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.split.android.client.SplitClientConfig;
import io.split.android.client.dtos.Condition;
import io.split.android.client.dtos.Matcher;
import io.split.android.client.dtos.MatcherGroup;
import io.split.android.client.dtos.Split;
import io.split.android.client.dtos.SplitChange;
import io.split.android.client.dtos.Status;
import io.split.android.client.events.SplitEventsManager;
import io.split.android.client.utils.Logger;
import io.split.android.engine.ConditionsTestUtil;
import io.split.android.engine.matchers.AllKeysMatcher;
import io.split.android.engine.matchers.CombiningMatcher;
import io.split.android.engine.segments.RefreshableMySegmentsFetcherProviderImpl;
import io.split.android.engine.segments.StaticMySegmentsFectherProvider;
import io.split.android.grammar.Treatments;
import io.split.android.helpers.SplitHelper;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

public class RefreshableSplitFetcherTest {
    @Test
    public void works_when_we_start_without_any_state() throws InterruptedException {
        works(0);
    }

    @Test
    public void works_when_we_start_with_any_state() throws InterruptedException {
        works(11L);
    }

    private void works(long startingChangeNumber) throws InterruptedException {
        AChangePerCallSplitChangeFetcher splitChangeFetcher = new AChangePerCallSplitChangeFetcher();

        Map<String, String> configs = SplitHelper.createConfigs(Arrays.asList("t1","t2"), Arrays.asList("{\"f1\":\"v1\"}", "{\"f2\":\"v2\"}"));

        splitChangeFetcher.configurations = configs;

        SplitEventsManager eventManager = new SplitEventsManager(SplitClientConfig.builder().build());
        RefreshableMySegmentsFetcherProviderImpl provider = StaticMySegmentsFectherProvider.get("key", eventManager);

        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(splitChangeFetcher, SplitParser.get(provider), eventManager, startingChangeNumber);

        // execute the fetcher for a little bit.
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> future = scheduledExecutorService.scheduleWithFixedDelay(fetcher, 0L, 1, TimeUnit.SECONDS);
        Thread.currentThread().sleep(3 * 1000L);

        scheduledExecutorService.shutdown();
        try {
            if (!scheduledExecutorService.awaitTermination(10L, TimeUnit.SECONDS)) {
                Logger.i("Executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = scheduledExecutorService.shutdownNow();
                Logger.i("Executor was abruptly shut down. These tasks will not be executed: %s", droppedTasks);
            }
        } catch (InterruptedException e) {
            // reset the interrupt.
            Thread.currentThread().interrupt();
        }


        assertThat(splitChangeFetcher.lastAdded(), is(greaterThan(startingChangeNumber)));
        assertThat(fetcher.changeNumber(), is(equalTo(splitChangeFetcher.lastAdded())));

        // all previous splits have been removed since they are dead
        for (long i = startingChangeNumber; i < fetcher.changeNumber(); i++) {
            assertThat("Asking for " + i + " " + fetcher.fetchAll(), fetcher.fetch("" + i), is(not(nullValue())));
            assertThat(fetcher.fetch("" + i).killed(), is(true));
        }

        ParsedCondition expectedParsedCondition = ParsedCondition.createParsedConditionForTests(CombiningMatcher.of(new AllKeysMatcher()), Lists.newArrayList(ConditionsTestUtil.partition("on", 10)));
        List<ParsedCondition> expectedListOfMatcherAndSplits = Lists.newArrayList(expectedParsedCondition);
        ParsedSplit expected = ParsedSplit.createParsedSplitForTests("" + fetcher.changeNumber(), (int) fetcher.changeNumber(), false, Treatments.OFF, expectedListOfMatcherAndSplits, null, fetcher.changeNumber(), 0, configs);

        ParsedSplit actual = fetcher.fetch("" + fetcher.changeNumber());

        assertThat(actual, is(equalTo(expected)));

    }

    @Test
    public void when_parser_fails_we_remove_the_experiment() throws InterruptedException {

        SplitEventsManager eventManager = new SplitEventsManager(SplitClientConfig.builder().build());
        RefreshableMySegmentsFetcherProviderImpl provider = StaticMySegmentsFectherProvider.get("key", eventManager);
        Split validSplit = new Split();
        validSplit.status = Status.ACTIVE;
        validSplit.seed = (int) -1;
        validSplit.conditions = Lists.newArrayList(ConditionsTestUtil.makeAllKeysCondition(Lists.newArrayList(ConditionsTestUtil.partition("on", 10))));
        validSplit.defaultTreatment = Treatments.OFF;
        validSplit.name = "-1";

        SplitChange validReturn = new SplitChange();
        validReturn.splits = Lists.newArrayList(validSplit);
        validReturn.since = -1L;
        validReturn.till = 0L;

        MatcherGroup invalidMatcherGroup = new MatcherGroup();
        invalidMatcherGroup.matchers = Lists.<Matcher>newArrayList();

        Condition invalidCondition = new Condition();
        invalidCondition.matcherGroup = invalidMatcherGroup;
        invalidCondition.partitions = Lists.newArrayList(ConditionsTestUtil.partition("on", 10));

        Split invalidSplit = new Split();
        invalidSplit.status = Status.ACTIVE;
        invalidSplit.seed = (int) -1;
        invalidSplit.conditions = Lists.newArrayList(invalidCondition);
        invalidSplit.defaultTreatment = Treatments.OFF;
        invalidSplit.name = "-1";

        SplitChange invalidReturn = new SplitChange();
        invalidReturn.splits = Lists.newArrayList(invalidSplit);
        invalidReturn.since = 0L;
        invalidReturn.till = 1L;

        SplitChange noReturn = new SplitChange();
        noReturn.splits = Lists.<Split>newArrayList();
        noReturn.since = 1L;
        noReturn.till = 1L;

        SplitChangeFetcher splitChangeFetcher = mock(SplitChangeFetcher.class);
        Mockito.when(splitChangeFetcher.fetch(-1L)).thenReturn(validReturn);
        Mockito.when(splitChangeFetcher.fetch(0L)).thenReturn(invalidReturn);
        Mockito.when(splitChangeFetcher.fetch(1L)).thenReturn(noReturn);


        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(splitChangeFetcher, SplitParser.get(provider), eventManager, -1L);

        // execute the fetcher for a little bit.
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleWithFixedDelay(fetcher, 0L, 1, TimeUnit.SECONDS);
        Thread.currentThread().sleep(5000L);

        scheduledExecutorService.shutdown();
        try {
            if (!scheduledExecutorService.awaitTermination(10L, TimeUnit.SECONDS)) {
                Logger.i("Executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = scheduledExecutorService.shutdownNow();
                Logger.i("Executor was abruptly shut down. These tasks will not be executed: %s", droppedTasks);
            }
        } catch (InterruptedException e) {
            // reset the interrupt.
            Thread.currentThread().interrupt();
        }

        assertThat(fetcher.changeNumber(), is(equalTo(1L)));

        // verify that the fetcher return null
        assertThat(fetcher.fetch("-1"), is(nullValue()));

    }

    @Test
    public void if_there_is_a_problem_talking_to_split_change_count_down_latch_is_not_decremented() throws InterruptedException {
        String key = "key";

        SplitEventsManager eventManager = new SplitEventsManager(SplitClientConfig.builder().build());
        SplitChangeFetcher splitChangeFetcher = mock(SplitChangeFetcher.class);
        Mockito.when(splitChangeFetcher.fetch(-1L)).thenThrow(new RuntimeException());

        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(splitChangeFetcher,
                SplitParser.get(StaticMySegmentsFectherProvider.get("key", eventManager)), eventManager, -1L);

        // execute the fetcher for a little bit.
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleWithFixedDelay(fetcher, 0L, 1, TimeUnit.SECONDS);
        Thread.currentThread().sleep(5000L);

        scheduledExecutorService.shutdown();
        try {
            if (!scheduledExecutorService.awaitTermination(10L, TimeUnit.SECONDS)) {
                Logger.i("Executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = scheduledExecutorService.shutdownNow();
                Logger.i("Executor was abruptly shut down. These tasks will not be executed: %s", droppedTasks);
            }
        } catch (InterruptedException e) {
            // reset the interrupt.
            Thread.currentThread().interrupt();
        }

        assertThat(fetcher.changeNumber(), is(equalTo(-1L)));

    }

    @Test
    public void works_with_user_defined_segments() throws InterruptedException {
        long startingChangeNumber = -1;
        String segmentName = "foosegment";
        AChangePerCallSplitChangeFetcher experimentChangeFetcher = new AChangePerCallSplitChangeFetcher(segmentName);

        SplitEventsManager eventManager = new SplitEventsManager(SplitClientConfig.builder().build());

        RefreshableMySegmentsFetcherProviderImpl provider = StaticMySegmentsFectherProvider.get("key", eventManager);

        RefreshableSplitFetcher fetcher = new RefreshableSplitFetcher(experimentChangeFetcher,
                SplitParser.get(provider), eventManager, startingChangeNumber);

        // execute the fetcher for a little bit.
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleWithFixedDelay(fetcher, 0L, 1, TimeUnit.SECONDS);
        Thread.currentThread().sleep(3000L);

        scheduledExecutorService.shutdown();
        try {
            if (!scheduledExecutorService.awaitTermination(10L, TimeUnit.SECONDS)) {
                Logger.i("Executor did not terminate in the specified time.");
                List<Runnable> droppedTasks = scheduledExecutorService.shutdownNow();
                Logger.i("Executor was abruptly shut down. These tasks will not be executed: %s", droppedTasks);
            }
        } catch (InterruptedException e) {
            // reset the interrupt.
            Thread.currentThread().interrupt();
        }


        assertThat(experimentChangeFetcher.lastAdded(), is(greaterThan(startingChangeNumber)));
        assertThat(fetcher.changeNumber(), is(equalTo(experimentChangeFetcher.lastAdded())));

        // all previous splits have been removed since they are dead
        for (long i = startingChangeNumber; i < fetcher.changeNumber(); i++) {
            assertThat("Asking for " + i + " " + fetcher.fetchAll(), fetcher.fetch("" + i), is(not(nullValue())));
            assertThat(fetcher.fetch("" + i).killed(), is(true));
        }

    }

}