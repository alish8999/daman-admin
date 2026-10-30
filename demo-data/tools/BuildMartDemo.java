import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Derives a shippable "market" demo database from a real client backup.
 *
 * <p><b>Why a derivation and not the backup itself.</b> The source is one Syrian mart's live
 * database: 51 real customers and 73 real suppliers with names, phone numbers and addresses, three
 * real logins, and the shop's true turnover. Handing that to a prospect — who might be a competitor
 * two streets away — would leak a paying client's confidential data. Everything identifying is
 * replaced here; what survives is the shape that makes a demo convincing.
 *
 * <p><b>What is kept:</b> the catalogue (product names, units, packagings, shelf prices — not
 * confidential for a grocery), and the sales rhythm: every one of the ~4,679 sales, their line
 * amounts, their dates and their daily peaks.
 *
 * <p><b>Why line AMOUNTS are preserved while product_id is remapped.</b> Cutting to 50 products
 * would otherwise orphan 69% of the line items and destroy half the sales. Instead each orphaned
 * line is re-pointed at a kept product (same category where possible) with its quantity and prices
 * untouched. Sale totals therefore do not move, which matters: 7,009 journal entries were posted
 * against those totals, and changing them would leave the Accounting page disagreeing with the
 * Reports page in a demo meant to showcase exactly those screens.
 *
 * Usage: BuildMartDemo <source.sqlite> <output.sqlite> [keepProducts]
 */
public class BuildMartDemo {

    /** Invented customers. Plausible Syrian names, deliberately not the real ones. */
    private static final String[] CLIENT_NAMES = {
        "أحمد الحلبي", "سمير الخطيب", "ليلى العيسى", "محمود الناصر", "رنا الشامي",
        "خالد الزعبي", "هدى المصري", "عمر الحموي", "نور الدين عباس", "ياسمين الحاج",
        "فادي الأحمد", "مها السيد", "زياد قاسم", "دارين سليمان", "باسل الرفاعي",
        "ريم الدالاتي", "طارق العلي", "سلمى الحسن", "وائل الديب", "عبير الطويل",
        "مازن الصالح", "لمى الجندي", "هيثم البكري", "نادين العمر", "رامي الشيخ",
        "جمانة الحلاق", "أيمن الكردي", "ديما القاسم", "سامر البيطار", "رولا النجار",
        "غسان الأسعد", "لينا الخولي", "فراس العبد", "ميساء التلاوي", "بشار الحموي",
        "رهف السمان", "عدنان الزين", "تالا الشهابي", "مروان الداية", "سارة الحمصي",
        "نبيل الأتاسي", "جودي الميداني", "كمال الحجار", "رغد البارودي", "أنس الخوري",
        "شذى المالكي", "حسان الطرابلسي", "علياء الصباغ", "يزن الحكيم", "ملك الجزائري",
        "توفيق الحوراني"
    };

    /** Invented suppliers — wholesaler-sounding, matching nothing real. */
    private static final String[] SUPPLIER_NAMES = {
        "شركة الأمانة للتوزيع", "مؤسسة النور التجارية", "الشرق للمواد الغذائية",
        "مجموعة الربيع", "وكالة الهدى", "شركة البركة", "مستودعات الواحة",
        "التجاري للتوريدات", "دار السلام للتجارة", "مؤسسة الفجر", "شركة الميزان",
        "الخليج للتوزيع", "بيت المستهلك", "مؤسسة الرحاب", "شركة السنابل",
        "الوفاء للتجارة", "مجموعة الأصيل", "مستودع الخير", "الريان للتوريد",
        "شركة المدى", "مؤسسة اللؤلؤة", "التوفيق للتجارة", "شركة الصفا",
        "مستودعات المروج", "الجود للتوزيع"
    };

    public static void main(String[] args) throws Exception {
        Path src = Path.of(args[0]), out = Path.of(args[1]);
        int keepCount = args.length > 2 ? Integer.parseInt(args[2]) : 50;

        Files.deleteIfExists(out);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.copy(src, out);
        System.out.println("copied source -> " + out.getFileName());

        String url = "jdbc:sqlite:" + out.toAbsolutePath().toString().replace("\\", "/");
        try (Connection c = DriverManager.getConnection(url)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=OFF");
            }

            List<Long> keep = topProducts(c, keepCount);
            System.out.println("keeping " + keep.size() + " products");

            Map<Long, Long> remap = buildRemap(c, keep);
            System.out.println("remapping " + remap.size() + " dropped products onto kept ones");

            remapColumn(c, "sale_items", remap);
            remapColumn(c, "purchase_items", remap);

            dropRowsForRemovedProducts(c, keep);
            deleteProducts(c, keep);
            deleteEmptyCategories(c);
            scrubParties(c);
            scrubIdentityAndFreeText(c);
            wipeUsersAndAudit(c);
            healthyStock(c);

            c.commit();
            // VACUUM cannot run inside a transaction, and this driver keeps one open while
            // autoCommit is false even after a commit.
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) { s.execute("VACUUM"); }
            report(c);
        }
        System.out.println("\nDone: " + out.toAbsolutePath());
    }

    private static List<Long> topProducts(Connection c, int n) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
                "SELECT product_id FROM sale_items WHERE product_id IS NOT NULL "
              + "GROUP BY product_id ORDER BY COUNT(*) DESC LIMIT ?")) {
            p.setInt(1, n);
            try (ResultSet r = p.executeQuery()) { while (r.next()) ids.add(r.getLong(1)); }
        }
        return ids;
    }

    /** Each dropped product maps to a kept one in the same category when there is one. */
    private static Map<Long, Long> buildRemap(Connection c, List<Long> keep) throws SQLException {
        Set<Long> keepSet = new HashSet<>(keep);
        Map<Long, Long> categoryOfKept = new LinkedHashMap<>();
        Map<Long, List<Long>> keptByCategory = new HashMap<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id, category_id FROM products")) {
            Map<Long, Long> catOf = new HashMap<>();
            while (r.next()) catOf.put(r.getLong(1), r.getLong(2));
            for (Long k : keep) {
                Long cat = catOf.get(k);
                categoryOfKept.put(k, cat);
                keptByCategory.computeIfAbsent(cat, x -> new ArrayList<>()).add(k);
            }
            Map<Long, Long> remap = new HashMap<>();
            int rr = 0;
            for (Map.Entry<Long, Long> e : catOf.entrySet()) {
                if (keepSet.contains(e.getKey())) continue;
                List<Long> sameCat = keptByCategory.get(e.getValue());
                Long target = (sameCat != null && !sameCat.isEmpty())
                        ? sameCat.get(Math.abs(e.getKey().hashCode()) % sameCat.size())
                        : keep.get(rr++ % keep.size());
                remap.put(e.getKey(), target);
            }
            return remap;
        }
    }

    private static void remapColumn(Connection c, String table, Map<Long, Long> remap) throws SQLException {
        int n = 0;
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE " + table + " SET product_id=? WHERE product_id=?")) {
            for (Map.Entry<Long, Long> e : remap.entrySet()) {
                p.setLong(1, e.getValue());
                p.setLong(2, e.getKey());
                n += p.executeUpdate();
            }
        }
        System.out.println("  " + table + ": " + n + " rows re-pointed");
        // A packaging belongs to its original product; after a remap it would dangle.
        try (Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE " + table + " SET packaging_id=NULL WHERE packaging_id IS NOT NULL "
                    + "AND packaging_id NOT IN (SELECT id FROM product_packagings WHERE product_id IN "
                    + "(SELECT product_id FROM " + table + "))");
        } catch (SQLException ignored) { /* purchase_items may not carry packaging_id in all builds */ }
    }

    /** Satellite rows for dropped products are deleted rather than remapped — none are load-bearing for a demo. */
    private static void dropRowsForRemovedProducts(Connection c, List<Long> keep) throws SQLException {
        String in = " NOT IN (" + csv(keep) + ")";
        String[][] targets = {
            {"product_packagings", "product_id"}, {"product_batches", "product_id"},
            {"product_ingredients", "product_id"}, {"stock_adjustments", "product_id"},
            {"quotation_items", "product_id"}, {"table_order_items", "product_id"},
            {"stocktake_session_items", "product_id"}, {"consignment_lot_items", "product_id"},
        };
        for (String[] t : targets) {
            try (Statement s = c.createStatement()) {
                int n = s.executeUpdate("DELETE FROM " + t[0] + " WHERE " + t[1] + in);
                if (n > 0) System.out.println("  " + t[0] + ": " + n + " rows dropped");
            } catch (SQLException e) { /* table absent in this build */ }
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM packaging_prices WHERE packaging_id NOT IN "
                    + "(SELECT id FROM product_packagings)");
        } catch (SQLException ignored) { }
    }

    private static void deleteProducts(Connection c, List<Long> keep) throws SQLException {
        try (Statement s = c.createStatement()) {
            int n = s.executeUpdate("DELETE FROM products WHERE id NOT IN (" + csv(keep) + ")");
            System.out.println("  products: " + n + " dropped");
        }
    }

    private static void deleteEmptyCategories(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            int n = s.executeUpdate("DELETE FROM categories WHERE id NOT IN "
                    + "(SELECT DISTINCT category_id FROM products)");
            System.out.println("  categories: " + n + " dropped");
        }
    }

    /** Every real person and business in the file is replaced here. */
    private static void scrubParties(Connection c) throws SQLException {
        int i = 0;
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id FROM clients ORDER BY id")) {
            List<Long> ids = new ArrayList<>();
            while (r.next()) ids.add(r.getLong(1));
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE clients SET name=?, phone_number=?, address=NULL WHERE id=?")) {
                for (Long id : ids) {
                    p.setString(1, CLIENT_NAMES[i % CLIENT_NAMES.length]
                            + (i >= CLIENT_NAMES.length ? " " + (i / CLIENT_NAMES.length + 1) : ""));
                    p.setString(2, "09" + String.format("%08d", 10000000 + i * 137));
                    p.setLong(3, id);
                    p.addBatch();
                    i++;
                }
                p.executeBatch();
            }
            System.out.println("  clients: " + i + " anonymised");
        }
        i = 0;
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT id FROM suppliers ORDER BY id")) {
            List<Long> ids = new ArrayList<>();
            while (r.next()) ids.add(r.getLong(1));
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE suppliers SET name=?, phone_number=?, address=NULL, notes=NULL WHERE id=?")) {
                for (Long id : ids) {
                    p.setString(1, SUPPLIER_NAMES[i % SUPPLIER_NAMES.length]
                            + (i >= SUPPLIER_NAMES.length ? " " + (i / SUPPLIER_NAMES.length + 1) : ""));
                    p.setString(2, "011" + String.format("%07d", 2000000 + i * 311));
                    p.setLong(3, id);
                    p.addBatch();
                    i++;
                }
                p.executeBatch();
            }
            System.out.println("  suppliers: " + i + " anonymised");
        }
    }

    /**
     * The shop's own identity, and every free-text field a person's name could be sitting in.
     *
     * <p>Found by {@code LeakCheck} after the first build: {@code installation_setup_settings}
     * carries the real shop's app_name, address, phone, phone2 and tax number, and
     * {@code receipt_settings.logo} its logo. Those are as identifying as the customer list, and
     * a demo that prints a real shop's name on its receipts is worse than one with no branding.
     *
     * <p>Free-text notes are blanked wholesale rather than inspected. Nobody can promise a
     * hand-typed note on a payment or a stock adjustment contains no name, and a demo needs none
     * of them.
     */
    private static void scrubIdentityAndFreeText(Connection c) throws SQLException {
        exec(c, "UPDATE installation_setup_settings SET app_name='Daman Demo Market',"
                + " address='Demo Street, Damascus', phone='0999000000', phone2=NULL, tax_number=NULL",
                "shop identity reset");
        exec(c, "UPDATE receipt_settings SET logo=NULL", "receipt logo cleared");
        exec(c, "UPDATE backup_settings SET backup_directory=NULL", "backup path cleared");

        String[][] blank = {
            {"payments", "notes"}, {"payments", "cancel_reason"},
            {"expenses", "notes"}, {"expenses", "description"},
            {"stock_adjustments", "note"}, {"categories", "description"},
            {"sales", "notes"}, {"purchases", "notes"}, {"shifts", "notes"},
            {"client_balance_adjustments", "reason"}, {"supplier_balance_adjustments", "reason"},
        };
        for (String[] b : blank) {
            exec(c, "UPDATE " + b[0] + " SET " + b[1] + "=NULL WHERE " + b[1] + " IS NOT NULL",
                    null);
        }
        // A shareholder is a named human being and a demo does not need one.
        exec(c, "UPDATE shareholders SET name='Demo Partner', notes=NULL", "shareholder renamed");
        System.out.println("  free-text notes blanked");
    }

    private static void exec(Connection c, String sql, String label) {
        try (Statement s = c.createStatement()) {
            int n = s.executeUpdate(sql);
            if (label != null) System.out.println("  " + label + " (" + n + ")");
        } catch (SQLException e) { /* table/column absent in this build */ }
    }

    /**
     * Users are emptied, not rewritten: DatabaseSeeder recreates `master` and the client admin
     * from the installation's own config on next boot, so no real password hash ships and the
     * demo gets whatever credentials its client config declares.
     */
    private static void wipeUsersAndAudit(Connection c) throws SQLException {
        for (String t : new String[]{"user_permissions", "users", "audit_logs"}) {
            try (Statement s = c.createStatement()) {
                int n = s.executeUpdate("DELETE FROM " + t);
                System.out.println("  " + t + ": " + n + " rows cleared");
            } catch (SQLException e) { /* absent */ }
        }
    }

    /**
     * Remapped quantities would otherwise leave kept products deeply negative.
     *
     * <p>Most products are set comfortably high, but six are deliberately left low. A demo exists
     * to show the features: with every product fully stocked the dashboard's low-stock panel and
     * the reorder report are empty, and the prospect never sees two of the things they are most
     * likely to care about. Six is enough to populate the panel without making the shop look
     * badly run.
     */
    private static void healthyStock(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE products SET quantity_in_stock = "
                    + "CASE WHEN weighed = 1 THEN 120 ELSE 250 END WHERE track_stock = 1");
            s.executeUpdate("UPDATE products SET quantity_in_stock = (id % 7) + 2 "
                    + "WHERE track_stock = 1 AND id IN "
                    + "(SELECT id FROM products WHERE track_stock = 1 ORDER BY id LIMIT 6)");
            System.out.println("  stock levelled (6 products left low, so the alerts panel has content)");
        }
    }

    private static void report(Connection c) throws SQLException {
        System.out.println("\n=== resulting demo ===");
        for (String t : new String[]{"products", "categories", "clients", "suppliers",
                "sales", "sale_items", "purchases", "payments", "acc_journal_entries", "users"}) {
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + t)) {
                System.out.printf("  %-20s %d%n", t, r.getInt(1));
            } catch (SQLException ignored) { }
        }
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT MIN(sale_date), MAX(sale_date) FROM sales")) {
            if (r.next()) System.out.println("  sales span " + r.getString(1) + " -> " + r.getString(2));
        }
    }

    private static String csv(List<Long> ids) {
        StringJoiner j = new StringJoiner(",");
        for (Long id : ids) j.add(String.valueOf(id));
        return j.toString();
    }
}
