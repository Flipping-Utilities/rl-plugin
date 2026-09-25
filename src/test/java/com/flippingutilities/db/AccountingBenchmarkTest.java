package com.flippingutilities.db;

import com.flippingutilities.accounting.AccountingEngine;
import com.flippingutilities.accounting.AccountingPlan;
import com.flippingutilities.accounting.AccountingResult;
import com.flippingutilities.db.accounting.SqliteAccountingStore;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.accounting.AccountingUiService.ReportKind;
import com.flippingutilities.ui.accounting.AccountingUiService.ReportQuery;
import com.flippingutilities.ui.accounting.AccountingUiService.Sort;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * Opt-in fixture: FLIPPING_ACCOUNTING_BENCHMARK_SOURCES=10000 (or 100000).
 * Timings are observations, not machine-dependent CI performance assertions.
 */
public class AccountingBenchmarkTest
{
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Instant START = Instant.parse("2020-01-01T00:00:00Z");

    @Test public void materializationAppendAndSqlReporting() throws Exception
    {
        String configured = System.getenv("FLIPPING_ACCOUNTING_BENCHMARK_SOURCES");
        Assume.assumeTrue("Set FLIPPING_ACCOUNTING_BENCHMARK_SOURCES=10000 or 100000", configured != null);
        int sourceCount = Integer.parseInt(configured);
        assertTrue("Use the documented fixture sizes", sourceCount == 10000 || sourceCount == 100000);
        SqliteStorage storage = new SqliteStorage(folder.newFile("benchmark.db"));
        try
        {
            storage.initializeSchema();
            long started = System.nanoTime();
            Connection connection = storage.getConnection();
            connection.setAutoCommit(false);
            try
            {
                for (int index = 0; index < sourceCount; index++)
                {
                    int item = 1000 + (index / 2) % 50;
                    storage.recordTrade("Benchmark", offer("offer-" + index, item, index % 2 == 0, index));
                }
                connection.commit();
            }
            catch (Exception failure)
            {
                connection.rollback();
                throw failure;
            }
            finally { connection.setAutoCommit(true); }
            long captureNanos = System.nanoTime() - started;
            SqliteAccountingStore store = storage.getAccountingStore();
            long account = store.findAccountId("Benchmark");
            AccountingPlan plan = new AccountingPlan("benchmark-plan", account,
                AccountingPlan.Mode.RECALCULATE, null, null, Map.of());
            started = System.nanoTime();
            store.activate(store.preview(plan));
            long materializationNanos = System.nanoTime() - started;
            ReportingRepository repository = new ReportingRepository(storage);
            ReportQuery query = new ReportQuery(List.of("Benchmark"), START, START.plusSeconds(sourceCount + 10),
                "Fixture", "", Sort.PROFIT, ReportKind.ITEMS, null, 0, 20, null, null);
            started = System.nanoTime();
            ReportingRepository.Page firstPage = repository.query(account, "Benchmark", query, 20, 0);
            long coldReportNanos = System.nanoTime() - started;
            started = System.nanoTime();
            for (int repeat = 0; repeat < 10; repeat++) repository.query(account, "Benchmark", query, 20, 0);
            long warmAverageNanos = (System.nanoTime() - started) / 10;
            assertEquals(50, firstPage.count);
            assertEquals(20, firstPage.rows.size());
            assertEquals(Long.valueOf((sourceCount / 2L) * 2000), firstPage.segment.amounts.profit.completeGp);
            started = System.nanoTime();
            storage.recordTrade("Benchmark", offer("append-buy", 1000, true, sourceCount + 1));
            store.reconcile(account);
            storage.recordTrade("Benchmark", offer("append-sale", 1000, false, sourceCount + 2));
            store.reconcile(account);
            long appendNanos = System.nanoTime() - started;
            started = System.nanoTime();
            AccountingResult rebuilt = new AccountingEngine().calculate(plan, store.loadSources(account), store.loadRecipes(account));
            long replayNanos = System.nanoTime() - started;
            ReportingRepository.Page after = repository.query(account, "Benchmark", query, 20, 0);
            long rebuiltProfit = rebuilt.getRealizations().stream().mapToLong(AccountingResult.Realization::getProfitGp).sum();
            assertEquals(Long.valueOf(rebuiltProfit), after.segment.amounts.profit.completeGp);
            String planDetail;
            try (Statement explain = connection.createStatement(); ResultSet row = explain.executeQuery(
                "EXPLAIN QUERY PLAN SELECT profit_gp FROM accounting_realizations WHERE plan_id='benchmark-plan' "
                    + "AND recognized_at>=0 AND recognized_at<9999999999999"))
            {
                StringBuilder detail = new StringBuilder();
                while (row.next()) detail.append(row.getString("detail")).append('\n');
                planDetail = detail.toString();
            }
            assertTrue(planDetail, planDetail.contains("INDEX"));
            System.out.printf("Accounting benchmark sources=%d capture=%.1fms materialize=%.1fms coldReport=%.1fms "
                    + "warmReportAvg=%.1fms twoAppendWrites=%.1fms fullReplay=%.1fms databaseBytes=%d%n%s",
                sourceCount, millis(captureNanos), millis(materializationNanos), millis(coldReportNanos),
                millis(warmAverageNanos), millis(appendNanos), millis(replayNanos), storage.getDbFile().length(), planDetail);
        }
        finally { storage.close(); }
    }

    private OfferEvent offer(String id, int item, boolean buy, int sequence)
    {
        int price = buy ? 100 : 120;
        return new OfferEvent(id, buy, item, 100, price, START.plusSeconds(sequence), 0,
            buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD, 0, 10, 100,
            null, false, "Benchmark", "Item " + item, price, price * 100);
    }

    private double millis(long nanos) { return nanos / 1_000_000.0; }
}
