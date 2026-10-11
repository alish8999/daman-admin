package com.daman.admin.config;

import com.daman.admin.entity.ChangelogEntry;
import com.daman.admin.repository.ChangelogEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time migration of changelog-library.json's original content into the
 * changelog_entries table (see ChangelogEntry's doc comment for why this
 * moved off a plain file). Same idempotency pattern as AdminUserSeeder —
 * table-empty check only, never touches existing rows, so an admin's own
 * edits/additions after first boot are never overwritten by this.
 *
 * <p>{@code @Order(10)} is load-bearing, not decoration. This seeder's guard
 * is "is the table empty", and {@link ChangelogDeployedBackfillRunner} inserts
 * rows of its own. An ApplicationRunner with no explicit order sorts LAST
 * (Ordered.LOWEST_PRECEDENCE), so without this the backfill runner would go
 * first on a fresh database, leave 3 rows behind, and this seeder would then
 * see a non-empty table and skip the entire original library for good.
 */
@Order(10)
@Component
@RequiredArgsConstructor
public class ChangelogEntrySeeder implements ApplicationRunner {

    private final ChangelogEntryRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        repository.saveAll(List.of(
                ChangelogEntry.builder().id("a4-invoice-redesign")
                        .en("Redesigned printed A4 invoices and receipts with a clearer, more modern layout — store logo, logo watermark, and clean product tables.")
                        .ar("إعادة تصميم فواتير وإيصالات A4 المطبوعة بتصميم أوضح وأكثر حداثة — شعار المتجر، علامة مائية للشعار، وجداول منتجات أوضح.")
                        .build(),
                ChangelogEntry.builder().id("save-as-pdf")
                        .en("Added a dedicated \"Save as PDF\" button next to Print on sales, purchases, and reports.")
                        .ar("تمت إضافة زر مخصص \"حفظ كملف PDF\" بجانب زر الطباعة في المبيعات والمشتريات والتقارير.")
                        .build(),
                ChangelogEntry.builder().id("cash-book-cash-in-out")
                        .en("Cash Book reports now show a full breakdown of cash in (sales, payments, deposits, shareholder contributions) and cash out (refunds, payments, withdrawals, purchases, expenses, shareholder withdrawals).")
                        .ar("تقارير دفتر النقدية تعرض الآن تفصيلاً كاملاً للنقد الداخل (المبيعات، الدفعات، الإيداعات، مساهمات الشركاء) والنقد الخارج (المرتجعات، الدفعات، السحوبات، المشتريات، المصاريف، سحوبات الشركاء).")
                        .build(),
                ChangelogEntry.builder().id("cash-book-simple-summary")
                        .en("Cash Book's quick summary now matches the detailed report's layout, just without the itemized product/expense lists.")
                        .ar("الملخص السريع لدفتر النقدية أصبح بنفس تنسيق التقرير المفصل، فقط بدون قوائم المنتجات والمصاريف التفصيلية.")
                        .build(),
                ChangelogEntry.builder().id("invoice-address-fix")
                        .en("Fixed the store address sometimes not appearing on printed invoices for stores that completed setup before this feature existed.")
                        .ar("تم إصلاح مشكلة عدم ظهور عنوان المتجر أحياناً في الفواتير المطبوعة للمتاجر التي أكملت الإعداد قبل إضافة هذه الميزة.")
                        .build(),
                ChangelogEntry.builder().id("add-sale-total-visibility")
                        .en("Made the sale total on the Add Sale screen bigger and easier to read, and fixed it getting pushed out of view when adding many items.")
                        .ar("تم تكبير إجمالي البيع في شاشة إضافة بيع ليكون أوضح، وتم إصلاح مشكلة اختفائه عند إضافة عدد كبير من الأصناف.")
                        .build(),
                ChangelogEntry.builder().id("add-sale-barcode-quantity-focus")
                        .en("Scanning a barcode now jumps straight to the quantity field with the value pre-selected, so you can type a new quantity immediately.")
                        .ar("مسح الباركود الآن ينتقل مباشرة إلى حقل الكمية مع تحديد القيمة تلقائياً، لتتمكن من كتابة كمية جديدة فوراً.")
                        .build(),
                ChangelogEntry.builder().id("sale-edit-paid-amount-fix")
                        .en("Fixed editing a sale sometimes incorrectly blocking a valid change to the amount paid.")
                        .ar("تم إصلاح مشكلة كانت تمنع أحياناً تعديل المبلغ المدفوع بشكل صحيح عند تعديل عملية بيع.")
                        .build(),
                ChangelogEntry.builder().id("sale-edit-history-clarity")
                        .en("Sale edit history now clearly shows what changed on each item, including price changes, instead of raw technical details.")
                        .ar("سجل تعديلات البيع يعرض الآن بوضوح ما الذي تغيّر في كل صنف، بما في ذلك تغييرات السعر، بدلاً من التفاصيل التقنية الخام.")
                        .build()
        ));
    }
}
