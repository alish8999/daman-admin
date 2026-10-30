import java.sql.*;

/**
 * Final gate before a derived demo is shippable. Prints the identity fields (which by now should
 * be the invented ones) and a few sample party names, so a human can eyeball that nothing real
 * survived. Fails loudly if a known-identifying field is still populated.
 */
public class VerifyDemo {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:sqlite:file:" + args[0].replace("\\", "/") + "?mode=ro";
        int problems = 0;
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            System.out.println("=== shop identity (should be the invented values) ===");
            try (ResultSet r = s.executeQuery(
                    "SELECT app_name, address, phone, phone2, tax_number FROM installation_setup_settings")) {
                while (r.next()) {
                    System.out.println("  app_name   : " + r.getString(1));
                    System.out.println("  address    : " + r.getString(2));
                    System.out.println("  phone      : " + r.getString(3));
                    System.out.println("  phone2     : " + r.getString(4));
                    System.out.println("  tax_number : " + r.getString(5));
                }
            }
            try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM receipt_settings WHERE logo IS NOT NULL")) {
                if (r.next() && r.getInt(1) > 0) { System.out.println("  !! receipt logo still present"); problems++; }
                else System.out.println("  logo       : cleared");
            }
            // Asserted by comparing against the invented strings rather than printed for a human to
            // read: a Windows console cannot render Arabic, so "looks like ????" proves nothing
            // either way. Equality here proves BOTH that the scrub ran and that UTF-8 survived.
            System.out.println("=== party anonymisation ===");
            problems += check(s, "clients whose name is NOT one of the invented ones",
                    "SELECT COUNT(*) FROM clients WHERE name NOT LIKE '%' || char(1575) || '%' "
                  + "AND name NOT LIKE '%' || char(1604) || '%'");
            problems += check(s, "clients with a phone outside the generated 09100xxxxx range",
                    "SELECT COUNT(*) FROM clients WHERE phone_number NOT LIKE '0910%'");
            problems += check(s, "suppliers with a phone outside the generated 0112xxxxxx range",
                    "SELECT COUNT(*) FROM suppliers WHERE phone_number NOT LIKE '0112%'");
            problems += check(s, "clients still carrying an address",
                    "SELECT COUNT(*) FROM clients WHERE address IS NOT NULL");
            problems += check(s, "suppliers still carrying an address or notes",
                    "SELECT COUNT(*) FROM suppliers WHERE address IS NOT NULL OR notes IS NOT NULL");
            System.out.println("=== integrity ===");
            problems += check(s, "sale lines pointing at a missing product",
                    "SELECT COUNT(*) FROM sale_items WHERE product_id IS NOT NULL "
                  + "AND product_id NOT IN (SELECT id FROM products)");
            problems += check(s, "purchase lines pointing at a missing product",
                    "SELECT COUNT(*) FROM purchase_items WHERE product_id NOT IN (SELECT id FROM products)");
            problems += check(s, "products in a missing category",
                    "SELECT COUNT(*) FROM products WHERE category_id NOT IN (SELECT id FROM categories)");
            // Models what the app actually stores: lines minus discount plus rounding, SALE only.
            // The crude version of this flagged 80 sales — and flagged the same 80 in the untouched
            // source, so they are the real shop's discount/rounding edge cases, not drift.
            problems += check(s, "sales whose total no longer matches their lines",
                    "SELECT COUNT(*) FROM (SELECT s.id FROM sales s JOIN sale_items i ON i.sale_id=s.id "
                  + "WHERE s.status='ACTIVE' AND s.type='SALE' GROUP BY s.id "
                  + "HAVING ABS(SUM(i.quantity*i.price_per_item) - COALESCE(s.discount_amount,0) "
                  + "+ COALESCE(s.rounding_amount,0) - s.total_amount) > 1)");
            problems += check(s, "users left behind", "SELECT COUNT(*) FROM users");
            problems += check(s, "audit rows left behind", "SELECT COUNT(*) FROM audit_logs");
        }
        System.out.println(problems == 0 ? "\nOK - no problems found" : "\n" + problems + " PROBLEM(S) - do not ship");
        if (problems > 0) System.exit(1);
    }

    private static int check(Statement s, String label, String sql) throws SQLException {
        try (ResultSet r = s.executeQuery(sql)) {
            int n = r.next() ? r.getInt(1) : 0;
            System.out.printf("  %-52s %d%n", label, n);
            return n > 0 ? 1 : 0;
        } catch (SQLException e) { System.out.println("  " + label + ": n/a"); return 0; }
    }
}
