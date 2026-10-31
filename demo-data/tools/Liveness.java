import java.sql.*;

/** Answers the only question that matters when a prospect first opens the demo: does it look alive? */
public class Liveness {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:sqlite:file:" + args[0].replace("\\", "/") + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            one(s, "sales today", "SELECT COUNT(*) FROM sales WHERE DATE(sale_date)=DATE('now')");
            one(s, "sales last 7 days", "SELECT COUNT(*) FROM sales WHERE DATE(sale_date)>=DATE('now','-6 days')");
            one(s, "sales this month", "SELECT COUNT(*) FROM sales WHERE strftime('%Y-%m',sale_date)=strftime('%Y-%m','now')");
            one(s, "distinct trading days", "SELECT COUNT(DISTINCT DATE(sale_date)) FROM sales");
            one(s, "unpaid/partial invoices (money owed to the shop)",
                    "SELECT COUNT(*) FROM sales WHERE payment_status IN ('UNPAID','PARTIAL')");
            one(s, "products below 10 in stock (low-stock alerts)",
                    "SELECT COUNT(*) FROM products WHERE track_stock=1 AND quantity_in_stock < 10");
            one(s, "clients with a non-zero balance", "SELECT COUNT(DISTINCT client_id) FROM sales "
                    + "WHERE client_id IS NOT NULL AND payment_status<>'PAID'");
        }
    }
    private static void one(Statement s, String label, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            System.out.printf("  %-48s %s%n", label, r.next() ? r.getString(1) : "?");
        } catch (SQLException e) { System.out.printf("  %-48s n/a%n", label); }
    }
}
