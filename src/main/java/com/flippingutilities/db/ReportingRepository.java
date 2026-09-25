package com.flippingutilities.db;

import com.flippingutilities.ui.accounting.AccountingUiService.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.flippingutilities.controller.accounting.LegacyReportingAdapter.itemKey;
import static com.flippingutilities.controller.accounting.LegacyReportingAdapter.recipeKey;

/** Bounded SQL reports over published facts. Reading a report never runs accounting. */
public final class ReportingRepository {
    private final SqliteStorage storage;
    private List<List<ReportRow>> stagedLegacyRows = Collections.emptyList();
    private Connection legacyStageConnection;
    private final String legacyStageTable = "accounting_report_legacy_" + UUID.randomUUID().toString().replace("-", "");
    public ReportingRepository(SqliteStorage storage) { this.storage = storage; }

    /** A rolled-back read transaction can also roll back its connection-local legacy staging. */
    public void invalidateLegacyStage() { legacyStageConnection = null; }

    public static final class Page {
        public final Segment segment;
        public final List<ReportRow> rows;
        public final long count;
        Page(Segment segment, List<ReportRow> rows, long count) {
            this.segment = segment; this.rows = rows; this.count = count;
        }
    }

    public void saveItemNames(Map<Integer, String> names) {
        synchronized (storage) {
            try (PreparedStatement insert = storage.getConnection().prepareStatement(
                "INSERT INTO accounting_items(item_id,item_name) VALUES (?,?) ON CONFLICT(item_id) DO UPDATE SET item_name=excluded.item_name")) {
                for (Map.Entry<Integer, String> entry : names.entrySet()) {
                    insert.setInt(1, entry.getKey()); insert.setString(2, entry.getValue()); insert.addBatch();
                }
                insert.executeBatch();
            } catch (SQLException e) { throw new IllegalStateException("Could not save item names", e); }
        }
    }

    public Map<Integer, String> itemNames() {
        synchronized (storage) {
            Map<Integer, String> result = new HashMap<>();
            try (Statement query = storage.getConnection().createStatement();
                 ResultSet rows = query.executeQuery("SELECT item_id,item_name FROM accounting_items")) {
                while (rows.next()) result.put(rows.getInt(1), rows.getString(2));
                return result;
            } catch (SQLException e) { throw new IllegalStateException("Could not read item names", e); }
        }
    }

    public String[] revisions(List<Long> accounts) {
        synchronized (storage) {
            StringBuilder source = new StringBuilder(), projection = new StringBuilder();
            try (PreparedStatement query = storage.getConnection().prepareStatement(
                "SELECT source_revision,reporting_revision,active_plan_id,dirty FROM accounting_state WHERE account_id=?")) {
                for (Long account : accounts) {
                    query.setLong(1, account);
                    try (ResultSet row = query.executeQuery()) {
                        source.append(account).append(':'); projection.append(account).append(':');
                        if (row.next()) {
                            source.append(row.getLong(1));
                            projection.append(row.getLong(2)).append(':').append(row.getString(3)).append(':').append(row.getBoolean(4));
                        }
                        source.append(';'); projection.append(';');
                    }
                }
                return new String[]{source.toString(), projection.toString()};
            } catch (SQLException e) { throw new IllegalStateException("Could not read report revision", e); }
        }
    }

    public Page query(long accountId, String account, ReportQuery request, int limit, int offset) {
        if (limit < 1 || offset < 0) throw new IllegalArgumentException("Invalid report page");
        synchronized (storage) {
            try {
                Connection connection = storage.getConnection();
                boolean recipe = request.kind == ReportKind.RECIPES || request.kind == ReportKind.RECIPE_FLIPS;
                boolean detail = request.kind == ReportKind.FLIPS || request.kind == ReportKind.RECIPE_FLIPS;
                String group = detail ? "r.flip_id" : recipe ? "COALESCE(c.recipe_key,'')" : "r.item_id";
                List<Object> args = new ArrayList<>(); args.add(accountId);
                String from = " FROM accounting_state s JOIN accounting_realizations r ON r.plan_id=s.active_plan_id " +
                    "LEFT JOIN accounting_recipes c ON c.account_id=s.account_id AND c.recipe_id=r.recipe_id " +
                    "LEFT JOIN accounting_items i ON i.item_id=r.item_id WHERE s.account_id=? AND r.kind" +
                    (recipe ? "='RECIPE'" : "<>'RECIPE'");
                if (request.fromInclusive != null) { from += " AND r.recognized_at>=?"; args.add(request.fromInclusive.toEpochMilli()); }
                if (request.toExclusive != null) { from += " AND r.recognized_at<?"; args.add(request.toExclusive.toEpochMilli()); }
                String title = recipe ? "COALESCE(json_extract(c.definition_json,'$.name'),c.recipe_key,'Unknown recipe')" : "COALESCE(i.item_name,'Item '||r.item_id)";
                if (request.search != null && !request.search.isEmpty()) {
                    from += " AND " + title + " LIKE ? ESCAPE '\\'";
                    args.add("%" + request.search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                }
                if (request.groupKey != null) {
                    // Keys include the account: opening a group cannot mix another account's inventory.
                    String prefix = account + (recipe ? ":recipe:" : ":item:");
                    if (!request.groupKey.startsWith(prefix)) from += " AND 0";
                    else {
                        from += recipe ? " AND COALESCE(c.recipe_key,'')=?" : " AND CAST(r.item_id AS TEXT)=?";
                        args.add(request.groupKey.substring(prefix.length()));
                    }
                }
                Segment segment;
                try (PreparedStatement statement = connection.prepareStatement("SELECT " + totals() + from)) {
                    bind(statement, args);
                    try (ResultSet row = statement.executeQuery()) {
                        row.next();
                        segment = new Segment(account, "Sale-time accounting", request.periodLabel, amounts(row),
                            row.getLong("flips"), row.getLong("quantity"), row.getLong("unknown_quantity"));
                    }
                }
                long count;
                try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM (SELECT 1" + from + " GROUP BY " + group + ")")) {
                    bind(statement, args);
                    try (ResultSet row = statement.executeQuery()) { row.next(); count = row.getLong(1); }
                }
                String sql = "SELECT " + group + " AS group_id,MIN(r.item_id) AS item_id,MIN(c.recipe_key) AS recipe_key," +
                    "MIN(" + title + ") AS title,MAX(r.recognized_at) AS occurred_at," + totals() + from + " GROUP BY " + group +
                    " ORDER BY " + order(request.sort) + ",CAST(group_id AS TEXT) ASC LIMIT ? OFFSET ?";
                List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(limit); pageArgs.add(offset);
                List<ReportRow> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bind(statement, pageArgs);
                    try (ResultSet row = statement.executeQuery()) {
                        while (row.next()) {
                            String key = recipe ? recipeKey(account, Objects.toString(row.getString("recipe_key"), "")) : itemKey(account, row.getInt("item_id"));
                            Long occurred = nullable(row, "occurred_at");
                            String description = detail ? (recipe ? "Recipe costs allocated across output sales" : "Matched at sale time") : "";
                            rows.add(new ReportRow("new:" + accountId + ":" + row.getString("group_id"), account,
                                "Sale-time accounting", row.getString("title"), description, amounts(row), row.getLong("quantity"),
                                row.getLong("flips"), detail ? null : recipe ? ReportKind.RECIPE_FLIPS : ReportKind.FLIPS,
                                detail ? null : key, occurred == null ? null : Instant.ofEpochMilli(occurred)));
                        }
                    }
                }
                return new Page(segment, rows, count);
            } catch (SQLException e) { throw new IllegalStateException("Could not query financial report", e); }
        }
    }

    /** Applies one global limit/offset in SQLite; Java never hydrates the preceding pages. */
    public List<ReportRow> globalPage(Map<Long, String> newAccounts, ReportQuery request,
                                     List<List<ReportRow>> legacyRows, int limit, int offset) {
        if (limit < 1 || limit > 500 || offset < 0) throw new IllegalArgumentException("Invalid report page");
        synchronized (storage) {
            try {
                Connection connection = storage.getConnection();
                stageLegacy(connection, legacyRows);
                List<String> selections = new ArrayList<>();
                List<Object> args = new ArrayList<>();
                if (!newAccounts.isEmpty()) {
                    selections.add(request.kind == ReportKind.INVENTORY
                        ? inventoryRows(newAccounts.keySet(), request, args)
                        : financialRows(newAccounts.keySet(), request, args));
                }
                selections.add("SELECT * FROM temp." + legacyStageTable);
                Sort sort = request.kind == ReportKind.INVENTORY && request.sort != Sort.QUANTITY ? Sort.TIME : request.sort;
                String sql = "SELECT * FROM (" + String.join(" UNION ALL ", selections) + ") ORDER BY "
                    + order(sort) + ",row_id COLLATE BINARY ASC LIMIT ? OFFSET ?";
                args.add(limit); args.add(offset);
                List<ReportRow> rows = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bind(statement, args);
                    try (ResultSet row = statement.executeQuery()) {
                        while (row.next()) {
                            String detail = row.getString("detail_kind");
                            Long time = nullable(row, "occurred_at");
                            rows.add(new ReportRow(row.getString("row_id"), row.getString("account_label"),
                                row.getString("method_label"), row.getString("title"), row.getString("description"),
                                amounts(row), row.getLong("quantity"), row.getLong("flips"),
                                detail == null ? null : ReportKind.valueOf(detail), row.getString("detail_key"),
                                time == null ? null : Instant.ofEpochMilli(time)));
                        }
                    }
                }
                return rows;
            } catch (SQLException failure) {
                legacyStageConnection = null;
                throw new IllegalStateException("Could not page the combined report", failure);
            } catch (RuntimeException failure) { legacyStageConnection = null; throw failure; }
        }
    }

    private String financialRows(Set<Long> accounts, ReportQuery request, List<Object> args) {
        boolean recipe = request.kind == ReportKind.RECIPES || request.kind == ReportKind.RECIPE_FLIPS;
        boolean detail = request.kind == ReportKind.FLIPS || request.kind == ReportKind.RECIPE_FLIPS;
        String group = detail ? "r.flip_id" : recipe ? "COALESCE(c.recipe_key,'')" : "r.item_id";
        String title = recipe ? "COALESCE(json_extract(c.definition_json,'$.name'),c.recipe_key,'Unknown recipe')"
            : "COALESCE(i.item_name,'Item '||r.item_id)";
        String prefix = recipe ? "a.display_name||':recipe:'" : "a.display_name||':item:'";
        String select = "SELECT 'new:'||s.account_id||':'||CAST(" + group + " AS TEXT) row_id,MIN(a.display_name) account_label,"
            + "'Sale-time accounting' method_label,MIN(" + title + ") title,? description,? detail_kind,"
            + (detail ? "NULL" : prefix + "||CAST(" + group + " AS TEXT)") + " detail_key,MAX(r.recognized_at) occurred_at," + totals();
        args.add(detail ? recipe ? "Recipe costs allocated across output sales" : "Matched at sale time" : "");
        args.add(detail ? null : recipe ? ReportKind.RECIPE_FLIPS.name() : ReportKind.FLIPS.name());
        String from = " FROM accounting_state s JOIN accounts a ON a.id=s.account_id "
            + "JOIN accounting_realizations r ON r.plan_id=s.active_plan_id "
            + "LEFT JOIN accounting_recipes c ON c.account_id=s.account_id AND c.recipe_id=r.recipe_id "
            + "LEFT JOIN accounting_items i ON i.item_id=r.item_id WHERE s.account_id IN ("
            + String.join(",", Collections.nCopies(accounts.size(), "?")) + ") AND r.kind"
            + (recipe ? "='RECIPE'" : "<>'RECIPE'");
        args.addAll(accounts);
        if (request.fromInclusive != null) { from += " AND r.recognized_at>=?"; args.add(request.fromInclusive.toEpochMilli()); }
        if (request.toExclusive != null) { from += " AND r.recognized_at<?"; args.add(request.toExclusive.toEpochMilli()); }
        if (request.search != null && !request.search.isEmpty()) {
            from += " AND " + title + " LIKE ? ESCAPE '\\'"; args.add(literalSearch(request.search));
        }
        if (request.groupKey != null) {
            from += " AND " + prefix + "||CAST(" + (recipe ? "COALESCE(c.recipe_key,'')" : "r.item_id") + " AS TEXT)=?";
            args.add(request.groupKey);
        }
        return select + from + " GROUP BY s.account_id," + group;
    }

    private String inventoryRows(Set<Long> accounts, ReportQuery request, List<Object> args) {
        String sql = "SELECT 'inventory:'||s.account_id||':'||l.item_id row_id,MIN(a.display_name) account_label,"
            + "'Open tracked inventory' method_label,MIN(COALESCE(i.item_name,'Item '||l.item_id)) title,"
            + "'Unrealized tracked purchases; quantities may include items used outside the GE.' description,"
            + "NULL detail_kind,NULL detail_key,MAX(l.acquired_at) occurred_at,"
            + "0 profit,1 profit_unknown,COALESCE(SUM(l.cost_gp),0) cost,COUNT(*)-COUNT(l.cost_gp) cost_unknown,"
            + "0 adjustment,0 adjustment_unknown,0 gross,1 gross_unknown,0 tax,1 tax_unknown,0 net,1 net_unknown,"
            + "COUNT(*)-COUNT(l.cost_gp) invested_unknown,SUM(l.quantity) quantity,0 flips,0 unknown_quantity,MAX(l.estimated) estimated"
            + " FROM accounting_open_lots l JOIN accounting_state s ON s.active_plan_id=l.plan_id "
            + "JOIN accounts a ON a.id=s.account_id LEFT JOIN accounting_items i ON i.item_id=l.item_id WHERE s.account_id IN ("
            + String.join(",", Collections.nCopies(accounts.size(), "?")) + ")";
        args.addAll(accounts);
        if (request.search != null && !request.search.isEmpty()) {
            sql += " AND COALESCE(i.item_name,'Item '||l.item_id) LIKE ? ESCAPE '\\'";
            args.add(literalSearch(request.search));
        }
        return sql + " GROUP BY s.account_id,l.item_id";
    }

    private String literalSearch(String search) {
        return "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private void stageLegacy(Connection connection, List<List<ReportRow>> rows) throws SQLException {
        if (legacyStageConnection == connection && stagedLegacyRows.equals(rows)) return;
        try (Statement ddl = connection.createStatement()) {
            ddl.execute("CREATE TEMP TABLE IF NOT EXISTS " + legacyStageTable + " ("
                + "row_id TEXT,account_label TEXT,method_label TEXT,title TEXT,description TEXT,detail_kind TEXT,"
                + "detail_key TEXT,occurred_at INTEGER,profit INTEGER,profit_unknown INTEGER,cost INTEGER,cost_unknown INTEGER,"
                + "adjustment INTEGER,adjustment_unknown INTEGER,gross INTEGER,gross_unknown INTEGER,tax INTEGER,tax_unknown INTEGER,"
                + "net INTEGER,net_unknown INTEGER,invested_unknown INTEGER,quantity INTEGER,flips INTEGER,unknown_quantity INTEGER,estimated INTEGER)");
            ddl.executeUpdate("DELETE FROM temp." + legacyStageTable);
        }
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO temp." + legacyStageTable + " VALUES ("
            + String.join(",", Collections.nCopies(25, "?")) + ")")) {
            int pending = 0;
            for (List<ReportRow> segment : rows) for (ReportRow row : segment) {
                List<Object> args = new ArrayList<>(Arrays.asList(row.id,row.accountLabel,row.methodLabel,row.title,
                    row.description,row.detailKind == null ? null : row.detailKind.name(),row.detailKey,
                    row.occurredAt == null ? null : row.occurredAt.toEpochMilli()));
                stageMoney(args,row.amounts.profit); stageMoney(args,row.amounts.cost);
                args.add(0L); args.add(0L); // Compatibility costs already include their coin adjustment.
                stageMoney(args,row.amounts.gross); stageMoney(args,row.amounts.tax); stageMoney(args,row.amounts.net);
                args.add(row.amounts.cost.completeGp == null ? Math.max(1,row.amounts.cost.unknownCount) : row.amounts.cost.unknownCount);
                args.add(row.quantity); args.add(row.count); args.add(row.amounts.profit.completeGp == null ? row.quantity : 0);
                args.add(row.amounts.profit.estimated);
                bind(insert,args); insert.addBatch();
                if (++pending == 500) { insert.executeBatch(); pending = 0; }
            }
            if (pending > 0) insert.executeBatch();
        }
        stagedLegacyRows = new ArrayList<>(rows);
        legacyStageConnection = connection;
    }

    private void stageMoney(List<Object> args, Money money) {
        args.add(money.knownSubtotalGp);
        args.add(money.completeGp == null ? Math.max(1, money.unknownCount) : money.unknownCount);
    }

    public long undatedCount(long accountId) {
        synchronized (storage) {
            try (PreparedStatement query = storage.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM accounting_realizations r JOIN accounting_state s ON s.active_plan_id=r.plan_id WHERE s.account_id=? AND r.recognized_at IS NULL")) {
                query.setLong(1, accountId);
                try (ResultSet row = query.executeQuery()) { row.next(); return row.getLong(1); }
            } catch (SQLException e) { throw new IllegalStateException("Could not count undated sales", e); }
        }
    }

    public Long sessionMillis(long accountId) {
        synchronized (storage) {
            try (PreparedStatement query = storage.getConnection().prepareStatement("SELECT accumulated_time FROM accounts WHERE id=?")) {
                query.setLong(1, accountId);
                try (ResultSet row = query.executeQuery()) { return row.next() ? nullable(row, "accumulated_time") : null; }
            } catch (SQLException e) { throw new IllegalStateException("Could not read tracked session time", e); }
        }
    }

    public Page inventory(long accountId, String account, String planId, ReportQuery request, int limit) {
        synchronized (storage) {
            String from = " FROM accounting_open_lots l LEFT JOIN accounting_items i ON i.item_id=l.item_id WHERE l.plan_id=?";
            List<Object> args = new ArrayList<>(); args.add(planId);
            if (request.search != null && !request.search.isEmpty()) {
                from += " AND COALESCE(i.item_name,'Item '||l.item_id) LIKE ? ESCAPE '\\'";
                args.add("%" + request.search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            }
            Money unavailable = new Money(null, 0, 0, false);
            try {
                Connection connection = storage.getConnection();
                long count, quantity, unknown, cost;
                boolean estimated;
                try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(DISTINCT l.item_id),COALESCE(SUM(l.quantity),0)," +
                    "COUNT(*)-COUNT(l.cost_gp),COALESCE(SUM(l.cost_gp),0),COALESCE(MAX(l.estimated),0)" + from)) {
                    bind(query, args);
                    try (ResultSet row = query.executeQuery()) {
                        row.next(); count=row.getLong(1); quantity=row.getLong(2); unknown=row.getLong(3); cost=row.getLong(4); estimated=row.getBoolean(5);
                    }
                }
                Segment segment = new Segment(account, "Open tracked inventory", "Current balance",
                    new Amounts(unavailable,new Money(unknown==0?cost:null,cost,unknown,estimated),unavailable,unavailable,unavailable),0,0,0);
                List<ReportRow> rows = new ArrayList<>();
                String sql = "SELECT l.item_id,MIN(COALESCE(i.item_name,'Item '||l.item_id)) title,MAX(l.acquired_at) occurred_at," +
                    "SUM(l.quantity) quantity,SUM(l.cost_gp) cost,COUNT(*)-COUNT(l.cost_gp) unknown,MAX(l.estimated) estimated" + from +
                    " GROUP BY l.item_id ORDER BY " + (request.sort==Sort.QUANTITY?"quantity DESC":"occurred_at DESC") + ",CAST(l.item_id AS TEXT) LIMIT ?";
                List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(limit);
                try (PreparedStatement query = connection.prepareStatement(sql)) {
                    bind(query,pageArgs);
                    try (ResultSet row=query.executeQuery()) {
                        while(row.next()) {
                            long known=row.getLong("cost"), missing=row.getLong("unknown");
                            Money value=new Money(missing==0?known:null,known,missing,row.getBoolean("estimated"));
                            Long time=nullable(row,"occurred_at");
                            rows.add(new ReportRow("inventory:"+accountId+":"+row.getInt("item_id"),account,"Open tracked inventory",row.getString("title"),
                                "Unrealized tracked purchases; quantities may include items used outside the GE.",
                                new Amounts(unavailable,value,unavailable,unavailable,unavailable),row.getLong("quantity"),0,null,null,
                                time==null?null:Instant.ofEpochMilli(time)));
                        }
                    }
                }
                return new Page(segment,rows,count);
            } catch(SQLException e) { throw new IllegalStateException("Could not read open inventory",e); }
        }
    }

    public Activity activity(long accountId, String planId, Instant from, Instant to) {
        synchronized (storage) {
            List<Object> args = new ArrayList<>(); args.add(planId); args.add(planId); args.add(accountId);
            String sql = "WITH reserved AS (SELECT source_id,SUM(quantity) quantity,SUM(cost_gp) amount," +
                "COUNT(*)-COUNT(cost_gp) unknown FROM accounting_allocations WHERE plan_id=? GROUP BY source_id)," +
                "sale_tax AS (SELECT source_id,SUM(tax_gp) amount,COUNT(*)-COUNT(tax_gp) unknown FROM accounting_realizations WHERE plan_id=? GROUP BY source_id)," +
                "activity AS (SELECT b.is_buy,b.estimated,CASE WHEN b.restricted=0 THEN b.quantity ELSE a.quantity END quantity," +
                "CASE WHEN b.restricted=0 THEN b.amount_gp WHEN a.unknown=0 THEN a.amount END amount," +
                "CASE WHEN b.is_buy=1 THEN 0 WHEN b.restricted=0 THEN b.tax_gp WHEN t.unknown=0 THEN t.amount END tax " +
                "FROM accounting_sources b LEFT JOIN reserved a ON a.source_id=b.source_id LEFT JOIN sale_tax t ON t.source_id=b.source_id " +
                "WHERE b.account_id=? AND (b.restricted=0 OR a.quantity>0)";
            if (from != null) { sql += " AND b.recognized_at>=?"; args.add(from.toEpochMilli()); }
            if (to != null) { sql += " AND b.recognized_at<?"; args.add(to.toEpochMilli()); }
            sql += ") SELECT is_buy,COALESCE(SUM(quantity),0) quantity,COALESCE(SUM(amount),0) amount," +
                "COUNT(*)-COUNT(amount) amount_unknown,COALESCE(SUM(tax),0) tax,COUNT(*)-COUNT(tax) tax_unknown," +
                "COALESCE(MAX(estimated),0) estimated FROM activity GROUP BY is_buy";
            long bought = 0, sold = 0;
            Money spent = Money.known(0), proceeds = Money.known(0), tax = Money.known(0);
            try (PreparedStatement query = storage.getConnection().prepareStatement(sql)) {
                bind(query, args);
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        if (row.getBoolean("is_buy")) { bought = row.getLong("quantity"); spent = money(row, "amount", row.getBoolean("estimated")); }
                        else { sold = row.getLong("quantity"); proceeds = money(row, "amount", row.getBoolean("estimated")); tax = money(row, "tax", row.getBoolean("estimated")); }
                    }
                }
                return new Activity(bought, sold, spent, proceeds, tax);
            } catch (SQLException e) { throw new IllegalStateException("Could not read trade activity", e); }
        }
    }

    public ReportDetails details(List<Long> accounts, String rowId) { return details(accounts, rowId, 0); }

    /** The result and Swing component count stay bounded even for one heavily allocated flip. */
    public ReportDetails details(List<Long> accounts, String rowId, int page) {
        if (page < 0) throw new IllegalArgumentException("Invalid detail page");
        int offset = Math.multiplyExact(page, 50);
        String[] identity = rowId.split(":", 3);
        if (identity.length != 3) throw new IllegalArgumentException("Invalid report row");
        long account = Long.parseLong(identity[1]);
        if (!accounts.contains(account)) throw new IllegalArgumentException("Report account changed");
        synchronized (storage) {
            List<String> lines = new ArrayList<>();
            try {
                Connection connection = storage.getConnection();
                String sql;
                List<Object> args;
                if (identity[0].equals("inventory")) {
                    sql = "SELECT l.quantity||' units acquired '||COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ',l.acquired_at/1000.0,'unixepoch'),'at an unknown time')||" +
                        "'; cost '||" + moneySql("l.cost_gp") + "||'; source '||l.source_id||', offset '||l.source_offset AS line " +
                        "FROM accounting_open_lots l JOIN accounting_state s ON s.active_plan_id=l.plan_id " +
                        "WHERE s.account_id=? AND l.item_id=? ORDER BY l.acquired_at,l.source_id LIMIT ? OFFSET ?";
                    args = Arrays.asList(account, Integer.parseInt(identity[2]), 51, offset);
                } else {
                    if (!identity[0].equals("new")) throw new IllegalArgumentException("Unknown detail kind");
                    sql = "WITH selected AS (SELECT r.* FROM accounting_realizations r JOIN accounting_state s ON s.active_plan_id=r.plan_id " +
                        "WHERE s.account_id=? AND r.flip_id=?), recipes AS (SELECT c.* FROM accounting_recipes c WHERE c.account_id=? " +
                        "AND c.recipe_id IN (SELECT recipe_id FROM selected)) SELECT line FROM (" +
                        "SELECT 0 AS section,0 AS ordinal,'' AS tie,'Whole-flip details include sales outside the selected report period.' AS line WHERE EXISTS(SELECT 1 FROM selected) " +
                        "UNION ALL SELECT 1,0,recipe_id,'Recipe '||COALESCE(json_extract(definition_json,'$.name'),recipe_key,'Unknown')||" +
                        "'; recorded executions: '||COALESCE(CAST(execution_count AS TEXT),'unknown (legacy)') FROM recipes " +
                        "UNION ALL SELECT 1,1,recipe_id,'Input cost and signed coin adjustment follow output-sale gross value; if all proceeds are zero, costs belong to the final sale.' FROM recipes " +
                        "UNION ALL SELECT 1,2,recipe_id,'Recorded recipe definition: unavailable (legacy)' FROM recipes WHERE definition_json IS NULL " +
                        "UNION ALL SELECT 2,CAST(j.key AS INTEGER),c.recipe_id||':input','Input per execution: '||json_extract(j.value,'$.quantity')||' × '||" +
                        "COALESCE(i.item_name,'Item '||json_extract(j.value,'$.id')) FROM recipes c,json_each(c.definition_json,'$.inputs') j " +
                        "LEFT JOIN accounting_items i ON i.item_id=json_extract(j.value,'$.id') " +
                        "UNION ALL SELECT 3,CAST(j.key AS INTEGER),c.recipe_id||':output','Output per execution: '||json_extract(j.value,'$.quantity')||' × '||" +
                        "COALESCE(i.item_name,'Item '||json_extract(j.value,'$.id')) FROM recipes c,json_each(c.definition_json,'$.outputs') j " +
                        "LEFT JOIN accounting_items i ON i.item_id=json_extract(j.value,'$.id') " +
                        "UNION ALL SELECT 4,r.recognized_at,r.realization_id,r.kind||' sale: '||r.quantity||' units at '||" +
                        "COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ',r.recognized_at/1000.0,'unixepoch'),'unknown time')||" +
                        "'; profit '||" + moneySql("r.profit_gp") + "||'; purchase cost '||" + moneySql("r.cost_gp") +
                        "||'; coin adjustment '||" + moneySql("r.adjustment_gp") + "||'; gross '||" + moneySql("r.gross_gp") +
                        "||'; tax '||" + moneySql("r.tax_gp") + "||CASE WHEN r.estimated=1 THEN ' (estimated amounts)' ELSE '' END FROM selected r " +
                        "UNION ALL SELECT 5,a.allocation_id,a.source_id,CASE WHEN a.is_buy=1 THEN 'Purchase' ELSE 'Sale' END||' source '||a.source_id||" +
                        "': item '||COALESCE(CAST(b.item_id AS TEXT),'unknown')||', '||a.quantity||' units from offset '||a.source_offset||', allocated amount '||" +
                        moneySql("a.cost_gp") + "||CASE WHEN b.restricted=1 THEN '; recipe-only evidence' ELSE '' END " +
                        "FROM selected r JOIN accounting_allocations a ON a.plan_id=r.plan_id AND a.realization_id=r.realization_id " +
                        "LEFT JOIN accounting_sources b ON b.account_id=? AND b.source_id=a.source_id" +
                        ") ORDER BY section,ordinal,tie LIMIT ? OFFSET ?";
                    args = Arrays.asList(account, identity[2], account, account, 51, offset);
                }
                boolean more;
                try (PreparedStatement query = connection.prepareStatement(sql)) {
                    bind(query, args);
                    try (ResultSet row = query.executeQuery()) { while (row.next()) lines.add(row.getString("line")); }
                }
                more = lines.size() > 50;
                if (more) lines.remove(50);
                if (page == 0 && lines.isEmpty()) throw new IllegalStateException("The selected report row is no longer available");
                return new ReportDetails(identity[0].equals("inventory") ? "Tracked purchase lots" : "Sources and allocations", lines, page, more);
            } catch (SQLException e) { throw new IllegalStateException("Could not read allocation details", e); }
        }
    }
    private static String moneySql(String expression) { return "COALESCE(CAST(" + expression + " AS TEXT)||' gp','unknown')"; }

    private static String totals() {
        return metric("profit", "r.profit_gp") + "," + metric("cost", "r.cost_gp") + "," +
            metric("adjustment", "r.adjustment_gp") + "," + metric("gross", "r.gross_gp") + "," +
            metric("tax", "r.tax_gp") + "," + metric("net", "r.gross_gp-r.tax_gp") +
            ",COALESCE(SUM(CASE WHEN r.cost_gp IS NULL OR r.adjustment_gp IS NULL THEN 1 ELSE 0 END),0) AS invested_unknown,COALESCE(SUM(r.quantity),0) AS quantity,COUNT(DISTINCT r.flip_id) AS flips," +
            "COALESCE(SUM(CASE WHEN r.profit_gp IS NULL THEN r.quantity ELSE 0 END),0) AS unknown_quantity," +
            "COALESCE(MAX(r.estimated),0) AS estimated";
    }
    private static String metric(String name, String expression) {
        return "COALESCE(SUM(" + expression + "),0) AS " + name + ",COUNT(*)-COUNT(" + expression + ") AS " + name + "_unknown";
    }
    private static String order(Sort sort) {
        switch (sort) {
            case PROFIT: return "CASE WHEN profit_unknown=0 THEN profit END DESC";
            case PROFIT_EACH: return "CASE WHEN profit_unknown=0 AND quantity>0 " +
                "THEN CAST(profit AS REAL)/quantity END DESC";
            case ROI: return "CASE WHEN profit_unknown=0 AND cost_unknown=0 AND adjustment_unknown=0 " +
                "AND cost+adjustment<>0 THEN CAST(profit AS REAL)/(cost+adjustment) END DESC";
            case QUANTITY: return "quantity DESC";
            default: return "occurred_at DESC";
        }
    }
    private static Amounts amounts(ResultSet row) throws SQLException {
        boolean estimated = row.getBoolean("estimated");
        Money cost = money(row, "cost", estimated), adjustment = money(row, "adjustment", estimated);
        long knownCost = Math.addExact(cost.knownSubtotalGp, adjustment.knownSubtotalGp);
        long unknownCost = row.getLong("invested_unknown");
        return new Amounts(money(row, "profit", estimated), new Money(unknownCost == 0 ? knownCost : null, knownCost, unknownCost, estimated),
            money(row, "gross", estimated), money(row, "tax", estimated), money(row, "net", estimated));
    }
    private static Money money(ResultSet row, String name, boolean estimated) throws SQLException {
        long sum = row.getLong(name), unknown = row.getLong(name + "_unknown");
        return new Money(unknown == 0 ? sum : null, sum, unknown, estimated);
    }
    private static Long nullable(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column); return row.wasNull() ? null : value;
    }
    private static void bind(PreparedStatement statement, List<Object> args) throws SQLException {
        for (int i=0; i<args.size(); i++) statement.setObject(i+1, args.get(i));
    }
}
