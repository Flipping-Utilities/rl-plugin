package com.flippingutilities.db;

import com.flippingutilities.accounting.AccountingPlan;
import com.flippingutilities.controller.accounting.AccountingCoordinator;
import com.flippingutilities.model.OfferEvent;
import com.flippingutilities.ui.accounting.AccountingUiService.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionException;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AccountingDetailsPaginationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    @Test public void inventoryDetailsStayBoundedAndDoNotRepeatOrSkipLots() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("lots.db"));
        try {
            storage.initializeSchema();
            storage.getConnection().setAutoCommit(false);
            for (int i=0;i<1003;i++) storage.recordTrade("Account", offer("buy-"+i,true,1,100,i));
            storage.getConnection().commit();
            storage.getConnection().setAutoCommit(true);
            long account = activate(storage);
            ReportingRepository reports = new ReportingRepository(storage);
            Set<String> lines = new HashSet<>();
            int page=0;
            ReportDetails result;
            do {
                result = reports.details(List.of(account),"inventory:"+account+":10",page);
                assertEquals(page,result.page);
                assertTrue(result.lines.size()<=50);
                for (String line:result.lines) assertTrue("Every persisted lot appears once",lines.add(line));
                page++;
            } while(result.hasMore);
            assertEquals(21,page);
            assertEquals(1003,lines.size());
            assertEquals(3,result.lines.size());
        } finally { storage.close(); }
    }

    @Test public void flipAllocationsArePagedAndLaterPagesRequireTheDisplayedRevision() throws Exception {
        SqliteStorage storage = new SqliteStorage(folder.newFile("allocations.db"));
        try {
            storage.initializeSchema();
            for(int i=0;i<103;i++) storage.recordTrade("Account",offer("buy-"+i,true,1,100,i));
            storage.recordTrade("Account",offer("sale",false,103,20600,104));
            activate(storage);
            AccountingCoordinator service = new AccountingCoordinator(storage,Runnable::run,()->true);
            ReportQuery query = new ReportQuery(List.of("Account"),null,null,"All","",Sort.TIME,ReportKind.FLIPS,null,0,20,null,null);
            ReportResult report=service.queryReport(query).join();
            assertEquals(1,report.rows.size());
            query=query.atRevision(report.sourceRevision,report.projectionRevision);
            String id=report.rows.get(0).id;
            int page=0;
            List<String> lines=new ArrayList<>();
            ReportDetails details;
            do {
                details=service.queryDetails(query,id,page++).join();
                assertTrue(details.lines.size()<=50);
                lines.addAll(details.lines);
            } while(details.hasMore);
            assertTrue("A heavily allocated flip requires multiple bounded pages",page>2);
            assertTrue(lines.stream().anyMatch(line->line.contains("buy-0")));
            assertTrue(lines.stream().anyMatch(line->line.contains("buy-102")));
            storage.recordTrade("Account",offer("later",true,1,100,110));
            try {
                service.queryDetails(query,id,1).join();
                fail("A new source revision must invalidate later source pages");
            } catch(CompletionException expected) { assertTrue(expected.getCause() instanceof StalePreviewException); }
        } finally { storage.close(); }
    }

    private long activate(SqliteStorage storage) {
        long account=storage.getAccountingStore().findAccountId("Account");
        AccountingPlan plan=new AccountingPlan("new-plan",account,AccountingPlan.Mode.RECALCULATE,null,null,Map.of());
        storage.getAccountingStore().activate(storage.getAccountingStore().preview(plan));
        return account;
    }
    private static OfferEvent offer(String id,boolean buy,int quantity,long amount,int seconds) {
        OfferEvent offer=new OfferEvent();
        offer.setUuid(id); offer.setOrderId(id); offer.setBuy(buy); offer.setItemId(10);
        offer.setCurrentQuantityInTrade(quantity); offer.setTotalQuantityInTrade(quantity);
        offer.setPrice((int)(amount/quantity)); offer.setCumulativeAmount(amount);
        offer.setTime(START.plusSeconds(seconds)); offer.setObservedAt(START.plusSeconds(seconds));
        offer.setState(buy?GrandExchangeOfferState.BOUGHT:GrandExchangeOfferState.SOLD);
        offer.setItemName("Item ten");
        return offer;
    }
}
