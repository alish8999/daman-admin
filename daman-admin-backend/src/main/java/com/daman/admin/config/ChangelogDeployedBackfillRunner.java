package com.daman.admin.config;

import com.daman.admin.entity.ChangelogEntry;
import com.daman.admin.repository.ChangelogEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-shot: closes the books on the changelog bullets that existed before the
 * {@code deployed} flag did, and adds the three bullets for the next release.
 *
 * <p>Two separate jobs, deliberately in one runner because each only makes
 * sense with the other — the whole point is to leave exactly the new bullets
 * showing as new:
 *
 * <ol>
 *   <li><b>Back catalogue → deployed.</b> Every row whose {@code deployed} is
 *       null predates the column, which means it was written for a release
 *       that has already gone out. Those become {@code true}.
 *   <li><b>The next release's bullets → not deployed.</b> Purchase drafts, the
 *       account-statement print design and the Appearance/theme settings, each
 *       inserted at {@code false}.
 * </ol>
 *
 * <p><b>Why the null check is the right guard.</b> "Mark everything currently
 * in the table as deployed" is only correct once — run it again after the next
 * batch of bullets is written and it would silently flag genuinely-new entries
 * as already shipped, which is precisely the state this exists to fix. Keying
 * off {@code deployed IS NULL} makes it self-limiting: after this runs, every
 * row has a real true/false, so a later boot finds nothing to backfill. That
 * holds only because new bullets are created at {@code false} and never null
 * (ChangelogLibraryService.saveAll) — if that ever changes, this guard stops
 * being safe.
 *
 * <p>Ordered after {@link ChangelogEntrySeeder} so a first-ever boot seeds the
 * original library and then immediately marks it deployed, rather than
 * leaving it null for a later run to sweep up.
 *
 * <p>Same shape as the other admin seeders: an ApplicationRunner that is a
 * cheap no-op once it has done its work, never a destructive re-run.
 */
@Slf4j
@Order(20)
@Component
@RequiredArgsConstructor
public class ChangelogDeployedBackfillRunner implements ApplicationRunner {

    private final ChangelogEntryRepository repository;

    @Override
    public void run(ApplicationArguments args) {
        markExistingAsDeployed();
        addPendingReleaseEntries();
    }

    private void markExistingAsDeployed() {
        List<ChangelogEntry> legacy = repository.findAll().stream()
                .filter(e -> e.getDeployed() == null)
                .toList();
        if (legacy.isEmpty()) {
            return;
        }
        legacy.forEach(e -> e.setDeployed(Boolean.TRUE));
        repository.saveAll(legacy);
        log.info("Changelog: marked {} pre-existing entries as already deployed", legacy.size());
    }

    private void addPendingReleaseEntries() {
        List<ChangelogEntry> pending = List.of(
                ChangelogEntry.builder().id("purchase-draft")
                        .en("Add Purchase now saves your work automatically. If you leave the page or the app closes part-way through, a banner on the Purchases page offers to resume the purchase exactly where you left off — supplier, items and quantities — or discard it. A draft left from an earlier day reminds you to re-check prices and the exchange rate before saving.")
                        .ar("شاشة إضافة مشتريات تحفظ عملك تلقائياً الآن. إذا غادرت الصفحة أو أُغلق التطبيق أثناء الإدخال، يظهر إشعار في صفحة المشتريات يتيح لك استئناف الفاتورة من حيث توقفت — المورّد والأصناف والكميات — أو حذف المسودة. وإذا كانت المسودة من يوم سابق يتم تنبيهك لمراجعة الأسعار وسعر الصرف قبل الحفظ.")
                        .deployed(Boolean.FALSE)
                        .build(),
                ChangelogEntry.builder().id("account-statement-print")
                        .en("Account statements now have a proper print design instead of printing the screen as-is. A4 prints as a formal statement of account — letterhead with your logo, a totals summary, the full table, and a balance-confirmation and signature line — and 58/80mm prints a version that actually fits receipt paper.")
                        .ar("كشف الحساب أصبح له تصميم طباعة احترافي بدلاً من طباعة الشاشة كما هي. مقاس A4 يُطبع ككشف حساب رسمي — ترويسة بشعار متجرك، ملخص للإجماليات، الجدول الكامل، وسطر لتأكيد الرصيد والتوقيع — ومقاس 58/80 ملم يُطبع بنسخة مناسبة لورق الإيصالات.")
                        .deployed(Boolean.FALSE)
                        .build(),
                ChangelogEntry.builder().id("appearance-theme")
                        .en("New Appearance settings let you change how the app looks: Classic for a plain, neutral look, or Custom to tune the colours to your own branding. The change applies across the app immediately.")
                        .ar("إعدادات المظهر الجديدة تتيح لك تغيير شكل التطبيق: النمط الكلاسيكي لمظهر بسيط ومحايد، أو النمط المخصص لضبط الألوان بما يناسب هويتك التجارية. ويُطبّق التغيير على التطبيق فوراً.")
                        .deployed(Boolean.FALSE)
                        .build());

        // Guarded per id, not on the table being empty: this has to be able to
        // add bullets to a library that already has plenty in it, while never
        // overwriting one the admin has since reworded by hand in the picker.
        List<ChangelogEntry> missing = pending.stream()
                .filter(e -> !repository.existsById(e.getId()))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        repository.saveAll(missing);
        log.info("Changelog: added {} entries for the next release", missing.size());
    }
}
