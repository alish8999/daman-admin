import java.sql.*;
import java.util.*;

/**
 * Looks for identifying data still present in the derived demo.
 *
 * <p>Prints only COUNTS and column names — never the values it finds — so running it cannot
 * itself spill the client data it is checking for. A non-zero count is a thing to go and scrub,
 * not a thing to read here.
 */
public class LeakCheck {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:sqlite:file:" + args[0].replace("\\", "/") + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url)) {
            System.out.println("=== free-text columns with any non-empty value ===");
            String[][] freeText = {
                {"sales", "notes"}, {"purchases", "notes"}, {"payments", "notes"},
                {"payments", "cancel_reason"}, {"payments", "receipt_number"},
                {"expenses", "description"}, {"expenses", "notes"},
                {"products", "description"}, {"categories", "description"},
                {"quotations", "notes"}, {"shareholders", "name"}, {"shareholders", "notes"},
                {"stock_adjustments", "note"}, {"client_balance_adjustments", "reason"},
                {"supplier_balance_adjustments", "reason"}, {"shifts", "notes"},
                {"consignment_settlements", "notes"}, {"fixed_assets", "name"},
            };
            for (String[] f : freeText) count(c, f[0], f[1]);

            System.out.println("=== shop-identity tables (row counts) ===");
            for (String t : new String[]{"receipt_settings", "installation_setup_settings",
                    "client_configs", "cash_book_settings", "kitchen_settings", "backup_settings"}) {
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    System.out.printf("  %-32s %d row(s)%n", t, r.getInt(1));
                } catch (SQLException e) { System.out.printf("  %-32s absent%n", t); }
            }

            System.out.println("=== columns on those tables ===");
            for (String t : new String[]{"receipt_settings", "installation_setup_settings", "client_configs"}) {
                try (Statement s = c.createStatement();
                     ResultSet r = s.executeQuery("PRAGMA table_info(" + t + ")")) {
                    StringBuilder sb = new StringBuilder("  " + t + ": ");
                    while (r.next()) sb.append(r.getString("name")).append(' ');
                    System.out.println(sb);
                } catch (SQLException ignored) { }
            }

            System.out.println("=== app_settings keys present ===");
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT setting_key FROM app_settings ORDER BY 1")) {
                while (r.next()) System.out.println("  " + r.getString(1));
            } catch (SQLException ignored) { }
        }
    }

    private static void count(Connection c, String table, String col) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table
                     + " WHERE " + col + " IS NOT NULL AND TRIM(" + col + ") <> ''")) {
            int n = r.getInt(1);
            if (n > 0) System.out.printf("  %-28s %-18s %d non-empty%n", table, col, n);
        } catch (SQLException ignored) { /* column or table absent */ }
    }
}
