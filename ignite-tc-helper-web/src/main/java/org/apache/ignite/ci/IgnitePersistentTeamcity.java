package org.apache.ignite.ci;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.MutableEntry;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.ci.analysis.Expirable;
import org.apache.ignite.ci.analysis.IVersionedEntity;
import org.apache.ignite.ci.analysis.LogCheckResult;
import org.apache.ignite.ci.analysis.RunStat;
import org.apache.ignite.ci.analysis.SingleBuildRunCtx;
import org.apache.ignite.ci.analysis.SuiteInBranch;
import org.apache.ignite.ci.db.DbMigrations;
import org.apache.ignite.ci.tcmodel.changes.Change;
import org.apache.ignite.ci.tcmodel.changes.ChangesList;
import org.apache.ignite.ci.tcmodel.conf.BuildType;
import org.apache.ignite.ci.tcmodel.hist.BuildRef;
import org.apache.ignite.ci.tcmodel.result.Build;
import org.apache.ignite.ci.tcmodel.result.problems.ProblemOccurrences;
import org.apache.ignite.ci.tcmodel.result.stat.Statistics;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrence;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrenceFull;
import org.apache.ignite.ci.tcmodel.result.tests.TestOccurrences;
import org.apache.ignite.ci.util.CacheUpdateUtil;
import org.apache.ignite.ci.util.CollectionUtil;
import org.apache.ignite.configuration.CacheConfiguration;
import org.jetbrains.annotations.NotNull;
import org.xml.sax.SAXParseException;

/**
 * Created by dpavlov on 03.08.2017
 */
public class IgnitePersistentTeamcity implements IAnalyticsEnabledTeamcity, ITeamcity, ITcAnalytics {

    //V1 caches, 1024 parts
    public static final String STAT = "stat";
    public static final String TEST_OCCURRENCE_FULL = "testOccurrenceFull";
    public static final String FINISHED_BUILDS = "finishedBuilds";
    public static final String PROBLEMS = "problems";

    //V2 caches, 32 parts
    public static final String TESTS_OCCURRENCES = "testOccurrences";
    public static final String TESTS_RUN_STAT = "testsRunStat";
    public static final String LOG_CHECK_RESULT = "logCheckResult";
    public static final String CHANGE_INFO_FULL = "changeInfoFull";
    public static final String CHANGES_LIST = "changesList";
    //todo need separate cache or separate key for run time because it is placed in statistics
    public static final String BUILDS_FAILURE_RUN_STAT = "buildsFailureRunStat";
    public static final String BUILDS = "builds";

    public static final String BUILD_QUEUE = "buildQueue";
    public static final String RUNNING_BUILDS = "runningBuilds";

    private final Ignite ignite;
    private final IgniteTeamcityHelper teamcity;
    private final String serverId;
    /** Statistics update in persisted cached enabled. */
    private boolean statUpdateEnabled = true;

    /** cached loads of full test occurrence. */
    private ConcurrentMap<String, CompletableFuture<TestOccurrenceFull>> testOccFullFutures = new ConcurrentHashMap<>();

    /** cached loads of queued builds for branch. */
    private ConcurrentMap<String, CompletableFuture<List<BuildRef>>> queuedBuildsFuts = new ConcurrentHashMap<>();

    /** cached loads of running builds for branch. */
    private ConcurrentMap<String, CompletableFuture<List<BuildRef>>> runningBuildsFuts = new ConcurrentHashMap<>();

    //todo: not good code to keep it static
    private static long lastTriggerMs = System.currentTimeMillis();

    public IgnitePersistentTeamcity(Ignite ignite, String srvId) {
        this(ignite, new IgniteTeamcityHelper(srvId));
    }

    private IgnitePersistentTeamcity(Ignite ignite, IgniteTeamcityHelper teamcity) {
        this.ignite = ignite;
        this.teamcity = teamcity;
        this.serverId = teamcity.serverId();

        DbMigrations migrations = new DbMigrations(ignite, teamcity.serverId());

        migrations.dataMigration(
            testOccurrencesCache(), this::addTestOccurrencesToStat,
            this::migrateOccurrencesToLatest,
            buildsCache(), this::addBuildToFailuresStat);
    }

    private IgniteCache<String, Build> buildsCache() {
        return getOrCreateCacheV2(ignCacheNme(BUILDS));
    }

    private IgniteCache<String, TestOccurrences> testOccurrencesCache() {
        return getOrCreateCacheV2(ignCacheNme(TESTS_OCCURRENCES));
    }

    private <K, V> IgniteCache<K, V> getOrCreateCacheV2(String name) {
        CacheConfiguration<K, V> ccfg = new CacheConfiguration<>(name);
        ccfg.setAffinity(new RendezvousAffinityFunction(false, 32));
        return ignite.getOrCreateCache(ccfg);
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<List<BuildType>> getProjectSuites(String projectId) {
        return teamcity.getProjectSuites(projectId);
    }

    /** {@inheritDoc} */
    @Override public String serverId() {
        return serverId;
    }

    private <K, V> V loadIfAbsent(String cacheName, K key, Function<K, V> loadFunction) {
        return loadIfAbsent(cacheName, key, loadFunction, (V v) -> true);
    }

    private <K, V> V loadIfAbsentV2(String cacheName, K key, Function<K, V> loadFunction) {
        return loadIfAbsent(getOrCreateCacheV2(ignCacheNme(cacheName)), key, loadFunction, (V v) -> true);
    }

    private <K, V> V loadIfAbsent(String cacheName, K key, Function<K, V> loadFunction, Predicate<V> saveValueFilter) {
        final IgniteCache<K, V> cache = ignite.getOrCreateCache(ignCacheNme(cacheName));

        return loadIfAbsent(cache, key, loadFunction, saveValueFilter);
    }

    private <K, V> V loadIfAbsent(IgniteCache<K, V> cache, K key, Function<K, V> loadFunction) {
        return loadIfAbsent(cache, key, loadFunction, null);
    }

    private <K, V> V loadIfAbsent(IgniteCache<K, V> cache, K key, Function<K, V> loadFunction,
        Predicate<V> saveValueFilter) {
        @Nullable final V persistedBuilds = cache.get(key);

        if (persistedBuilds != null)
            return persistedBuilds;

        final V loaded = loadFunction.apply(key);

        if (saveValueFilter == null || saveValueFilter.test(loaded))
            cache.put(key, loaded);

        return loaded;
    }

    private <K, V> V timedLoadIfAbsentOrMerge(String cacheName, int seconds, K key, BiFunction<K, V, V> loadWithMerge) {
        final IgniteCache<K, Expirable<V>> hist = ignite.getOrCreateCache(ignCacheNme(cacheName));
        @Nullable final Expirable<V> persistedBuilds = hist.get(key);
        if (persistedBuilds != null) {
            if (persistedBuilds.isAgeLessThanSecs(seconds))
                return persistedBuilds.getData();
        }

        V apply = loadWithMerge.apply(key, persistedBuilds != null ? persistedBuilds.getData() : null);

        final Expirable<V> newVal = new Expirable<>(System.currentTimeMillis(), apply);

        hist.put(key, newVal);

        return apply;
    }

    /** {@inheritDoc} */
    @Override public List<BuildRef> getFinishedBuilds(String projectId, String branch) {
        final SuiteInBranch suiteInBranch = new SuiteInBranch(projectId, branch);
        return timedLoadIfAbsentOrMerge(FINISHED_BUILDS, 60, suiteInBranch,
            (key, persistedValue) -> {
                List<BuildRef> builds;
                try {
                    builds = teamcity.getFinishedBuilds(projectId, branch);
                }
                catch (Exception e) {
                    if (Throwables.getRootCause(e) instanceof FileNotFoundException) {
                        System.err.println("Build history not found for build : " + projectId + " in " + branch);
                        builds = Collections.emptyList();
                    }
                    else
                        throw e;
                }
                return mergeByIdToHistoricalOrder(persistedValue, builds);
            });
    }

    @NotNull
    private List<BuildRef> mergeByIdToHistoricalOrder(List<BuildRef> persistedVal, List<BuildRef> mostActualVal) {
        final SortedMap<Integer, BuildRef> merge = new TreeMap<>();
        if (persistedVal != null)
            persistedVal.forEach(b -> merge.put(b.getId(), b));

        mostActualVal.forEach(b -> merge.put(b.getId(), b)); //to overwrite data from persistence by values from REST

        return new ArrayList<>(merge.values());
    }

    //loads build history with following parameter: defaultFilter:false,state:finished

    /** {@inheritDoc} */
    @Override public List<BuildRef> getFinishedBuildsIncludeSnDepFailed(String projectId, String branch) {
        final SuiteInBranch suiteInBranch = new SuiteInBranch(projectId, branch);
        return timedLoadIfAbsentOrMerge("finishedBuildsIncludeFailed", 60, suiteInBranch,
            (key, persistedValue) -> {
                List<BuildRef> failed = teamcity.getFinishedBuildsIncludeSnDepFailed(projectId, branch);

                return mergeByIdToHistoricalOrder(persistedValue,
                    failed);
            });
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<List<BuildRef>> getRunningBuilds(String branch) {
        int defaultSecs = 60;
        int secondsUseCached = getTriggerRelCacheValidSecs(defaultSecs);

        return CacheUpdateUtil.loadAsyncIfAbsentOrExpired(
            getOrCreateCacheV2(ignCacheNme(RUNNING_BUILDS)),
            Strings.nullToEmpty(branch),
            runningBuildsFuts,
            teamcity::getRunningBuilds,
            secondsUseCached,
            secondsUseCached == defaultSecs);
    }

    public static int getTriggerRelCacheValidSecs(int defaultSecs) {
        long msSinceTrigger = System.currentTimeMillis() - lastTriggerMs;
        long secondsSinceTigger = TimeUnit.MILLISECONDS.toSeconds(msSinceTrigger);
        return Math.min((int)secondsSinceTigger, defaultSecs);
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<List<BuildRef>> getQueuedBuilds(@Nullable final String branch) {
        int defaultSecs = 60;
        int secondsUseCached = getTriggerRelCacheValidSecs(defaultSecs);

        return CacheUpdateUtil.loadAsyncIfAbsentOrExpired(
            getOrCreateCacheV2(ignCacheNme(BUILD_QUEUE)),
            Strings.nullToEmpty(branch),
            queuedBuildsFuts,
            teamcity::getQueuedBuilds,
            secondsUseCached,
            secondsUseCached == defaultSecs);
    }

    /** {@inheritDoc} */
    @Nullable
    @Override public Build getBuild(String href) {
        final IgniteCache<String, Build> cache = buildsCache();

        @Nullable final Build persistedBuild = cache.get(href);

        if (persistedBuild != null) {
            if (!persistedBuild.isOutdatedEntityVersion())
                return persistedBuild;
        }

        final Build loaded = realLoadBuild(href);
        //can't reload, but cached has value
        if (loaded.isFakeStub() && persistedBuild != null && persistedBuild.isOutdatedEntityVersion()) {
            persistedBuild._version = persistedBuild.latestVersion();
            cache.put(href, persistedBuild);
            
            return persistedBuild;
        }

        if (loaded.isFakeStub() || loaded.hasFinishDate()) {
            cache.put(href, loaded);

            if (statUpdateEnabled) {
                // may check if branch is tracked and save anyway
                //todo first touch of build here will cause build and its stat will be diverged
                addBuildToFailuresStat(loaded);
            }
        }

        return loaded;
    }

    private void addBuildToFailuresStat(Build loaded) {
        if (loaded.isFakeStub())
            return;

        String suiteId = loaded.suiteId();
        if (Strings.isNullOrEmpty(suiteId))
            return;

        buildsFailureRunStatCache().invoke(suiteId, (entry, arguments) -> {
            String buildCfgId = entry.getKey();

            Build build = (Build)arguments[0];

            RunStat val = entry.getValue();

            if (val == null)
                val = new RunStat(buildCfgId);

            val.addBuildRun(build);

            entry.setValue(val);

            return null;
        }, loaded);
    }

    private Build realLoadBuild(String href1) {
        try {
            return teamcity.getBuild(href1);
        }
        catch (Exception e) {
            if (Throwables.getRootCause(e) instanceof FileNotFoundException) {
                System.err.println("Exception " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Build.createFakeStub();// save null result, because persistence may refer to some non-existent build on TC
            }
            else
                throw e;
        }
    }

    @NotNull private String ignCacheNme(String cache) {
        return ignCacheNme(cache, serverId);
    }

    @NotNull public static String ignCacheNme(String cache, String serverId) {
        return serverId + "." + cache;
    }

    /** {@inheritDoc} */
    @Override public String host() {
        return teamcity.host();
    }

    /** {@inheritDoc} */
    @Override public ProblemOccurrences getProblems(String href) {
        return loadIfAbsent(PROBLEMS,
            href,
            teamcity::getProblems);
    }

    /** {@inheritDoc} */
    @Override public TestOccurrences getTests(String href) {
        String hrefForDb = DbMigrations.removeCountFromRef(href);

        return loadIfAbsent(testOccurrencesCache(),
            hrefForDb,  //hack to avoid test reloading from store in case of href filter replaced
            hrefIgnored -> {
                TestOccurrences loadedTests = teamcity.getTests(href);

                //todo first touch of build here will cause build and its stat will be diverged
                addTestOccurrencesToStat(loadedTests);

                return loadedTests;
            });
    }

    private void addTestOccurrencesToStat(TestOccurrences val) {
        if (!statUpdateEnabled)
            return;

        //may use invoke all
        for (TestOccurrence next : val.getTests()) {
            addTestOccurrenceToStat(next);
        }
    }

    /** {@inheritDoc} */
    @Override public Statistics getBuildStat(String href) {
        return loadIfAbsent(STAT,
            href,
            href1 -> {
                try {
                    return teamcity.getBuildStat(href1);
                }
                catch (Exception e) {
                    if (Throwables.getRootCause(e) instanceof FileNotFoundException) {
                        e.printStackTrace();
                        return new Statistics();// save null result, because persistence may refer to some  unexistent build on TC
                    }
                    else
                        throw e;
                }
            });
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<TestOccurrenceFull> getTestFull(String href) {
        return CacheUpdateUtil.loadAsyncIfAbsent(
            ignite.getOrCreateCache(ignCacheNme(TEST_OCCURRENCE_FULL)),
            href,
            testOccFullFutures,
            teamcity::getTestFull);
    }

    /** {@inheritDoc} */
    @Override public Change getChange(String href) {
        return loadIfAbsentV2(CHANGE_INFO_FULL, href, href1 -> {
            try {
                return teamcity.getChange(href1);
            }
            catch (Exception e) {
                if (Throwables.getRootCause(e) instanceof FileNotFoundException) {
                    System.err.println("Change history not found for href : " + href);

                    return new Change();
                }
                if (Throwables.getRootCause(e) instanceof SAXParseException) {
                    System.err.println("Change data seems to be invalid: " + href);

                    return new Change();
                }
                else
                    throw e;
            }
        });
    }

    /** {@inheritDoc} */
    @Override public ChangesList getChangesList(String href) {
        return loadIfAbsentV2(CHANGES_LIST, href, href1 -> {
            try {
                return teamcity.getChangesList(href1);
            }
            catch (Exception e) {
                if (Throwables.getRootCause(e) instanceof FileNotFoundException) {
                    System.err.println("Change List not found for href : " + href);

                    return new ChangesList();
                }
                else
                    throw e;
            }
        });
    }

    /** {@inheritDoc} */
    @Override public List<RunStat> topTestFailing(int cnt) {
        return CollectionUtil.top(allTestAnalysis(), cnt, Comparator.comparing(RunStat::getFailRate));
    }

    /** {@inheritDoc} */
    @Override public List<RunStat> topTestsLongRunning(int cnt) {
        return CollectionUtil.top(allTestAnalysis(), cnt, Comparator.comparing(RunStat::getAverageDurationMs));
    }

    /** {@inheritDoc} */
    @Override public Function<String, RunStat> getTestRunStatProvider() {
        return name -> name == null ? null : testRunStatCache().get(name);
    }

    private Stream<RunStat> allTestAnalysis() {
        return StreamSupport.stream(testRunStatCache().spliterator(), false)
            .map(Cache.Entry::getValue);
    }

    private Cache<String, RunStat> testRunStatCache() {
        return getOrCreateCacheV2(ignCacheNme(TESTS_RUN_STAT));
    }

    /** {@inheritDoc} */
    @Override public Function<String, RunStat> getBuildFailureRunStatProvider() {
        return name -> name == null ? null : buildsFailureRunStatCache().get(name);
    }

    private Stream<RunStat> buildsFailureAnalysis() {
        return StreamSupport.stream(buildsFailureRunStatCache().spliterator(), false)
            .map(Cache.Entry::getValue);
    }

    /**
     * @return cache from suite name to its failure statistics
     */
    private IgniteCache<String, RunStat> buildsFailureRunStatCache() {
        return getOrCreateCacheV2(ignCacheNme(BUILDS_FAILURE_RUN_STAT));
    }

    private IgniteCache<Integer, LogCheckResult> logCheckResultCache() {
        return getOrCreateCacheV2(ignCacheNme(LOG_CHECK_RESULT));
    }

    private void addTestOccurrenceToStat(TestOccurrence next) {
        String name = next.getName();
        if (Strings.isNullOrEmpty(name))
            return;

        if (next.isMutedTest() || next.isIgnoredTest())
            return;

        testRunStatCache().invoke(name, new EntryProcessor<String, RunStat, Object>() {
            @Override
            public Object process(MutableEntry<String, RunStat> entry,
                Object... arguments) throws EntryProcessorException {

                String key = entry.getKey();

                TestOccurrence testOccurrence = (TestOccurrence)arguments[0];

                RunStat val = entry.getValue();
                if (val == null)
                    val = new RunStat(key);

                val.addTestRun(testOccurrence);

                entry.setValue(val);

                return null;
            }
        }, next);
    }

    private void migrateOccurrencesToLatest(TestOccurrences val) {
        if (!statUpdateEnabled)
            return;

        //may use invoke all
        for (TestOccurrence next : val.getTests()) {
            migrateTestOneOcurrToAddToLatest(next);
        }
    }

    private void migrateTestOneOcurrToAddToLatest(TestOccurrence next) {
        String name = next.getName();
        if (Strings.isNullOrEmpty(name))
            return;

        if (next.isMutedTest() || next.isIgnoredTest())
            return;

        testRunStatCache().invoke(name, (entry, arguments) -> {
            String key = entry.getKey();
            TestOccurrence testOccurrence = (TestOccurrence)arguments[0];

            RunStat val = entry.getValue();
            if (val == null)
                val = new RunStat(key);

            val.addTestRunToLatest(testOccurrence);

            entry.setValue(val);

            return null;
        }, next);
    }

    /** {@inheritDoc} */
    public List<RunStat> topFailingSuite(int cnt) {
        return CollectionUtil.top(buildsFailureAnalysis(), cnt, Comparator.comparing(RunStat::getFailRate));
    }

    /** {@inheritDoc} */
    @Override public void close() {

    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<File> unzipFirstFile(CompletableFuture<File> fut) {
        return teamcity.unzipFirstFile(fut);
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<File> downloadBuildLogZip(int id) {
        return teamcity.downloadBuildLogZip(id);
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<LogCheckResult> analyzeBuildLog(Integer buildId, SingleBuildRunCtx ctx) {
        return loadFutureIfAbsentVers(logCheckResultCache(), buildId,
            k -> teamcity.analyzeBuildLog(buildId, ctx));
    }

    public String getThreadDumpCached(Integer buildId) {
        IgniteCache<Integer, LogCheckResult> entries = logCheckResultCache();
        LogCheckResult logCheckResult = entries.get(buildId);
        if (logCheckResult == null)
            return null;

        return logCheckResult.getLastThreadDump();
    }

    /**
     * @param cache
     * @param key
     * @param submitFunction caching of already submitted computations should be done by this function.
     * @param <K>
     * @param <V>
     * @return
     */
    public <K, V extends IVersionedEntity> CompletableFuture<V> loadFutureIfAbsentVers(IgniteCache<K, V> cache,
        K key,
        Function<K, CompletableFuture<V>> submitFunction) {
        @Nullable final V persistedValue = cache.get(key);

        if (persistedValue != null && !persistedValue.isOutdatedEntityVersion())
            return CompletableFuture.completedFuture(persistedValue);

        CompletableFuture<V> apply = submitFunction.apply(key);

        return apply.thenApplyAsync(val -> {
            cache.put(key, val);
            return val;
        });
    }

    /** {@inheritDoc} */
    public void setExecutor(ExecutorService executor) {
        this.teamcity.setExecutor(executor);
    }

    /** {@inheritDoc} */
    @Override public void triggerBuild(String id, String name, boolean queueAtTop) {
        lastTriggerMs = System.currentTimeMillis();

        teamcity.triggerBuild(id, name, queueAtTop);
    }

    public void setStatUpdateEnabled(boolean statUpdateEnabled) {
        this.statUpdateEnabled = statUpdateEnabled;
    }
}
