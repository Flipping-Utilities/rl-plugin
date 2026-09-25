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
    public ReportingRepository(SqliteStorage storage) { this.storage = storage; }

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
                String group = detail ? "r.flip_id" : recipe ? "c.recipe_key" : "r.item_id";
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
                        from += recipe ? " AND c.recipe_key=?" : " AND CAST(r.item_id AS TEXT)=?";
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
                            String key = recipe ? recipeKey(account, row.getString("recipe_key")) : itemKey(account, row.getInt("item_id"));
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

    public ReportDetails details(List<Long> accounts, String rowId) {
        String[] identity = rowId.split(":", 3);
        if (identity.length != 3) throw new IllegalArgumentException("Invalid report row");
        long account = Long.parseLong(identity[1]);
        if (!accounts.contains(account)) throw new IllegalArgumentException("Report account changed");
        synchronized (storage) {
            List<String> lines = new ArrayList<>();
            Set<String> recipes = new LinkedHashSet<>();
            try {
                Connection connection = storage.getConnection();
                if (identity[0].equals("inventory")) {
                    try (PreparedStatement query = connection.prepareStatement(
                        "SELECT l.* FROM accounting_open_lots l JOIN accounting_state s ON s.active_plan_id=l.plan_id WHERE s.account_id=? AND l.item_id=? ORDER BY l.acquired_at,l.source_id")) {
                        bind(query,Arrays.asList(account,Integer.parseInt(identity[2])));
                        try (ResultSet row=query.executeQuery()) {
                            while(row.next()) {
                                Long acquired=nullable(row,"acquired_at");
                                lines.add(row.getLong("quantity")+" units acquired "+(acquired==null?"at an unknown time":Instant.ofEpochMilli(acquired))+
                                    "; cost "+gp(nullable(row,"cost_gp"))+"; source "+row.getString("source_id")+", offset "+row.getLong("source_offset"));
                            }
                        }
                    }
                    return new ReportDetails("Tracked purchase lots",lines);
                }
                lines.add("Whole-flip details include sales outside the selected report period.");
                try (PreparedStatement query = connection.prepareStatement(
                    "SELECT r.*,a.display_name FROM accounting_realizations r JOIN accounting_state s ON s.active_plan_id=r.plan_id " +
                    "JOIN accounts a ON a.id=s.account_id WHERE s.account_id=? AND r.flip_id=? ORDER BY r.recognized_at,r.realization_id")) {
                    bind(query, Arrays.asList(account, identity[2]));
                    try (ResultSet row = query.executeQuery()) {
                        while (row.next()) {
                            Long date = nullable(row, "recognized_at");
                            lines.add(row.getString("kind") + " sale: " + row.getLong("quantity") + " units at " +
                                (date == null ? "unknown time" : Instant.ofEpochMilli(date)) + "; profit " + gp(nullable(row,"profit_gp")) +
                                "; purchase cost " + gp(nullable(row,"cost_gp")) + "; coin adjustment " + gp(nullable(row,"adjustment_gp")) +
                                "; gross " + gp(nullable(row,"gross_gp")) + "; tax " + gp(nullable(row,"tax_gp")) +
                                (row.getBoolean("estimated") ? " (estimated amounts)" : ""));
                            if (row.getString("recipe_id") != null) recipes.add(row.getString("recipe_id"));
                        }
                    }
                }
                try (PreparedStatement query = connection.prepareStatement(
                    "SELECT a.*,b.item_id,b.recognized_at,b.restricted,b.order_id FROM accounting_allocations a " +
                    "JOIN accounting_state s ON s.active_plan_id=a.plan_id " +
                    "JOIN accounting_realizations r ON r.plan_id=a.plan_id AND r.realization_id=a.realization_id " +
                    "LEFT JOIN accounting_sources b ON b.account_id=s.account_id AND b.source_id=a.source_id " +
                    "WHERE s.account_id=? AND r.flip_id=? ORDER BY a.allocation_id")) {
                    bind(query, Arrays.asList(account, identity[2]));
                    try (ResultSet row = query.executeQuery()) {
                        while (row.next()) lines.add((row.getBoolean("is_buy") ? "Purchase" : "Sale") + " source " + row.getString("source_id") +
                            ": item " + row.getInt("item_id") + ", " + row.getLong("quantity") + " units from offset " + row.getLong("source_offset") +
                            ", allocated amount " + gp(nullable(row,"cost_gp")) + (row.getBoolean("restricted") ? "; recipe-only evidence" : ""));
                    }
                }
                for (String recipe : recipes) {
                    try (PreparedStatement query = connection.prepareStatement("SELECT recipe_key,definition_json,execution_count,recorded_at FROM accounting_recipes WHERE account_id=? AND recipe_id=?")) {
                        bind(query, Arrays.asList(account, recipe));
                        try (ResultSet row = query.executeQuery()) {
                            if (row.next()) {
                                Long count = nullable(row,"execution_count");
                                lines.add("Recipe " + row.getString("recipe_key") + "; recorded executions: " + (count == null ? "unknown (legacy)" : count));
                                String definition = row.getString("definition_json");
                                if (definition == null) lines.add("Recorded recipe definition: unavailable (legacy)");
                                else {
                                    com.flippingutilities.utilities.Recipe saved = new com.google.gson.Gson().fromJson(definition, com.flippingutilities.utilities.Recipe.class);
                                    Map<Integer, String> names = itemNames();
                                    lines.add("Recorded recipe: " + saved.getName());
                                    for (com.flippingutilities.utilities.RecipeItem input : saved.getInputs()) lines.add("Input per execution: " + input.getQuantity() + " × " + names.getOrDefault(input.getId(), "Item " + input.getId()));
                                    for (com.flippingutilities.utilities.RecipeItem output : saved.getOutputs()) lines.add("Output per execution: " + output.getQuantity() + " × " + names.getOrDefault(output.getId(), "Item " + output.getId()));
                                }
                                lines.add("Input cost and signed coin adjustment are allocated by output-sale gross value. If all output values are zero, costs belong to the final sale.");
                            }
                        }
                    }
                }
                if (lines.isEmpty()) throw new IllegalStateException("The selected report row is no longer available");
                return new ReportDetails("Sources and allocations", lines);
            } catch (SQLException e) { throw new IllegalStateException("Could not read allocation details", e); }
        }
    }
    private static String gp(Long value) { return value == null ? "unknown" : value + " gp"; }

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
