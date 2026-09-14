package com.lucklotter.config;

import com.lucklotter.domain.AdminUser;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.service.IngestionService;
import com.lucklotter.service.RedemptionCodeGenerator;
import com.lucklotter.service.RetentionScanService;
import com.lucklotter.web.dto.TransactionIngestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Seeds a demo business, an admin login, and a transaction history that
 * produces flags on the first scan (G4, §13).
 *
 * <p>Off unless {@code lucklotter.seed.enabled=true}, and a no-op if any
 * business already exists, so it can't overwrite real pilot data.
 *
 * <p>Transactions go in through {@link IngestionService} rather than straight
 * SQL, so the seeded cadence is computed by the same code the API uses — demo
 * data can't drift from production behaviour.
 *
 * <h2>On the backdated history</h2>
 *
 * Part two writes flags and offers with timestamps spread over the previous
 * eight weeks. That is legitimate precisely because every customer here is
 * invented: the whole dataset is a fixture, so a fixture flag is no more a
 * fabrication than a fixture transaction. What would <em>not</em> be
 * legitimate is backdating a flag for a real customer, or drawing a chart from
 * numbers the system never recorded.
 *
 * <p>Without it the overview shows one bar and a recovery rate off six flags,
 * which says nothing about whether the mechanism works over time.
 *
 * <h2>On the generated crowd</h2>
 *
 * <p>§22 asks for a demo dataset of several hundred customers. The named
 * profiles are 26 and are hand-written because each one is looked up by name
 * during a demo; they are not a cast that can be scaled by repeating it. So
 * {@link #CROWD_SIZE} more are generated underneath them from a fixed seed, and
 * their rhythms, baskets and quiet periods are drawn rather than typed.
 *
 * <p>Generated is not the same as arbitrary. The seed is fixed, so the dataset
 * is identical on every boot; the gaps are jittered around a rhythm rather than
 * spread uniformly; and the spends are drawn from bands shaped like a café's
 * takings. A demo whose numbers move between restarts cannot be used to make a
 * claim, and one where every customer spends the same amount leaves the
 * priority score with nothing to rank.
 *
 * <h2>On the scan that runs at the end</h2>
 *
 * Seeding finishes by running the real cadence-break scan once, which flags the
 * quiet customers and dispatches their offers over SMTP. So a demo stack has
 * mail waiting in the inbox the moment it finishes booting, with nobody having
 * pressed anything — which is the claim being made about this system, that the
 * notification path runs by itself.
 *
 * <p>It calls {@link RetentionScanService#scan} — the same method the 02:00
 * schedule calls, not a demo shortcut past it. Waiting for that schedule would
 * leave a freshly started demo showing an empty inbox for up to a day, and
 * writing the offers straight to {@code SENT} instead would put the one claim
 * worth demonstrating beyond reach of the demo.
 */
@Component
@ConditionalOnProperty(name = "lucklotter.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final BusinessRepository businesses;
    private final AdminUserRepository adminUsers;
    private final CustomerRepository customers;
    private final RetentionFlagRepository flags;
    private final OfferRepository offers;
    private final IngestionService ingestion;
    private final RetentionScanService retentionScan;
    private final RedemptionCodeGenerator redemptionCodes;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;

    public DemoDataSeeder(BusinessRepository businesses,
                          AdminUserRepository adminUsers,
                          CustomerRepository customers,
                          RetentionFlagRepository flags,
                          OfferRepository offers,
                          IngestionService ingestion,
                          RetentionScanService retentionScan,
                          RedemptionCodeGenerator redemptionCodes,
                          PasswordEncoder passwordEncoder,
                          @Value("${lucklotter.seed.admin-email}") String adminEmail,
                          @Value("${lucklotter.seed.admin-password}") String adminPassword) {
        this.businesses = businesses;
        this.adminUsers = adminUsers;
        this.customers = customers;
        this.flags = flags;
        this.offers = offers;
        this.ingestion = ingestion;
        this.retentionScan = retentionScan;
        this.redemptionCodes = redemptionCodes;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    /**
     * A customer who is quiet right now and will flag on the next scan.
     *
     * @param visits      how many transactions to generate
     * @param cadenceDays the gap between them
     * @param quietDays   how long ago the most recent visit was
     * @param spend       what they usually leave at the till, in shillings
     */
    private record Profile(String ref, String name, int visits, int cadenceDays, int quietDays,
                           int spend, String email, String phone) {
    }

    /**
     * A customer who was flagged in the past. {@code recoveredAfterDays} of
     * null means they never came back, so their flag is still open.
     */
    private record History(String ref, String name, int cadenceDays, int flaggedWeeksAgo,
                           Integer recoveredAfterDays, int spend, String email, String phone) {
    }

    /**
     * What a customer typically spends per visit, and how common that is.
     *
     * <p>The weights describe a café rather than an even spread: most people buy
     * a coffee and a pastry, and a handful come in for beans, cake and a round
     * for the table. That is the shape the priority score is trying to find, so
     * a dataset where everyone spends the same amount would leave its value
     * component with nothing to rank.
     */
    private record SpendBand(int min, int max, int weight) {
    }

    private static final SpendBand[] SPEND_BANDS = {
        new SpendBand(150, 350, 40),
        new SpendBand(350, 650, 35),
        new SpendBand(650, 1200, 18),
        new SpendBand(1200, 2400, 7),
    };

    /**
     * Chosen to exercise every path the dashboard can show, not just the happy
     * one: customers who should flag, customers who shouldn't, and customers
     * who flag but cannot be contacted.
     */
    private static final Profile[] PROFILES = {
        // Weekly regulars gone quiet — threshold is 7 x 1.5 = 10.5 days, so
        // these flag.
        new Profile("POS-1001", "Aisha Njeri", 6, 7, 34, 320, "aisha@example.test", "+254700000001"),
        new Profile("POS-1002", "Brian Otieno", 8, 6, 41, 1180, "brian@example.test", null),
        new Profile("POS-1003", "Sheila Atieno", 5, 9, 45, 260, null, "+254700000003"),
        // Fortnightly regular, quiet well past 14 x 1.5 = 21 days.
        new Profile("POS-1004", "Diana Kamau", 6, 14, 52, 2400, "diana@example.test", "+254700000004"),
        // Flaggable, but no email and no phone: lands at NO_CONTACT (FR-5). Also
        // left deliberately unnamed — a till that recorded nothing but a
        // reference is exactly the row that has no way to reach anyone, and it
        // keeps the dashboard's no-name path and the offer email's no-name
        // greeting fallback both covered by the demo data.
        new Profile("POS-1005", null, 5, 7, 30, 180, null, null),
        new Profile("POS-1006", null, 4, 10, 38, 540, null, null),
        // Still on cadence — must not flag.
        new Profile("POS-2001", "Eric Wanjiru", 7, 7, 2, 300, "eric@example.test", "+254700000011"),
        new Profile("POS-2002", "Faith Mumbi", 9, 5, 1, 220, "faith@example.test", null),
        new Profile("POS-2003", "Grace Wairimu", 6, 12, 4, 1450, "grace@example.test", "+254700000013"),
        // Quiet, but below MIN_TRANSACTIONS: no cadence, so not flaggable (FR-2).
        new Profile("POS-3001", "Henry Kiprop", 2, 7, 60, 400, "henry@example.test", null),
        new Profile("POS-3002", "Irene Chebet", 1, 0, 90, 700, "irene@example.test", "+254700000022"),
    };

    /** Eight weeks of prior activity, most of whom came back. */
    private static final History[] HISTORY = {
        new History("POS-4001", "James Mwangi", 7, 8, 6, 260, "james@example.test", "+254700000101"),
        new History("POS-4002", "Karen Wekesa", 6, 8, 9, 1800, "karen@example.test", null),
        new History("POS-4003", "Lucy Kimani", 10, 7, 5, 340, "lucy@example.test", "+254700000103"),
        new History("POS-4004", "Martin Langat", 7, 7, null, 420, "martin@example.test", null),
        new History("POS-4005", "Nadia Achieng", 5, 6, 4, 2100, "nadia@example.test", "+254700000105"),
        new History("POS-4006", "Oscar Kiprono", 9, 6, 12, 480, "oscar@example.test", null),
        new History("POS-4007", "Paul Mwangi", 7, 5, 7, 900, "paul@example.test", "+254700000107"),
        new History("POS-4008", "Quincy Barasa", 12, 5, null, 300, "quincy@example.test", null),
        new History("POS-4009", "Rita Nyambura", 6, 4, 8, 1250, "rita@example.test", "+254700000109"),
        new History("POS-4010", "Samuel Rotich", 8, 4, 6, 380, "samuel@example.test", null),
        new History("POS-4011", "Tanya Wafula", 7, 3, 5, 620, "tanya@example.test", "+254700000111"),
        new History("POS-4012", "Umar Hassan", 11, 3, null, 1600, "umar@example.test", null),
        new History("POS-4013", "Vera Chelimo", 6, 2, 9, 280, "vera@example.test", "+254700000113"),
        new History("POS-4014", "Wesley Ochieng", 9, 2, 7, 520, "wesley@example.test", null),
        new History("POS-4015", "Xena Muthoni", 7, 1, 4, 1050, "xena@example.test", "+254700000115"),
    };

    // --- the crowd behind the named customers ----------------------------------

    /**
     * Customers invented in bulk, so the dashboard has a customer base rather
     * than a cast.
     *
     * <p>§22 asks for several hundred. The named profiles above are 26, and they
     * cannot be scaled by repeating them: each one is looked up by name in a
     * demo to make a specific point — the row with no contact details, the row
     * with no name, the ones still on rhythm. So the crowd is generated
     * underneath them and the named ones stay the ones a presenter points at.
     */
    private static final int CROWD_SIZE = 320;

    /** Of those, how many are currently quiet enough to flag. */
    private static final int CROWD_QUIET_PERCENT = 12;

    /**
     * Customers flagged in a previous week, to give the win-back programme a
     * track record.
     *
     * <p>Without these the retention report's return rate rests on the 26 named
     * customers, and a rate over a handful is a fact about the fixture rather
     * than about the business. §7 is explicit that the estimate must be withheld
     * when its cohort is too thin; the honest fix is a cohort, not a lower bar.
     */
    private static final int CROWD_HISTORY_SIZE = 180;

    /** Roughly this share of a flagged crowd came back inside the window. */
    private static final int CROWD_RECOVERY_PERCENT = 45;

    /**
     * Fixed seed.
     *
     * <p>Every boot of the demo produces the same ~500 people, with the same
     * cadences and the same baskets. A fixture that reshuffled at every restart
     * would make the product look non-deterministic — the same query answered
     * differently on two machines — when the only unstable thing is the data it
     * was pointed at. It also means a figure quoted in one demo is the figure
     * the next demo shows.
     */
    private static final long CROWD_SEED = 20260813L;

    /**
     * Visit gaps, weighted by repetition.
     *
     * <p>Weekly and fortnightly dominate, which is what a café's regulars
     * actually look like. The 60-day tail is there because a customer base has
     * one, and because the trigger's clamp has to have something to clamp.
     */
    private static final int[] CADENCES = {
        3, 4, 5, 7, 7, 7, 7, 10, 10, 14, 14, 14, 21, 21, 30, 30, 45, 60,
    };

    private static final String[] GIVEN_NAMES = {
        "Alice", "Anne", "Beatrice", "Caroline", "Catherine", "Cynthia", "Dorothy",
        "Eunice", "Gladys", "Hannah", "Joyce", "Judith", "Mercy", "Miriam",
        "Nancy", "Purity", "Rose", "Sharon", "Winnie", "Yvonne",
        "Anthony", "Charles", "Daniel", "Dennis", "Elijah", "Felix", "Geoffrey",
        "Isaac", "Joseph", "Kevin", "Michael", "Patrick", "Peter", "Simon",
        "Stephen", "Timothy", "Victor", "Wycliffe",
    };

    private static final String[] FAMILY_NAMES = {
        "Achieng", "Chebet", "Gitau", "Juma", "Karanja", "Kariuki", "Kibet",
        "Kimani", "Kipchoge", "Langat", "Maina", "Mbugua", "Mugo", "Mutiso",
        "Mwangi", "Ndegwa", "Njoroge", "Nyongesa", "Odhiambo", "Omondi",
        "Onyango", "Otieno", "Rotich", "Wambui", "Wanjala", "Wekesa",
    };

    /**
     * Assigned in {@link #run}. Reused rather than re-created so the whole
     * crowd comes off one stream and one seed, which is what makes the dataset
     * reproducible: drawing from several generators would leave the order they
     * are consulted in as an unwritten second seed.
     */
    private Random random;


    @Override
    public void run(String... args) {
        if (businesses.count() > 0) {
            log.info("Seed skipped: a business already exists");
            return;
        }

        Business business = new Business();
        business.setName("Kaldi's Coffee House");
        business.setDefaultDealType(DealType.PERCENT_OFF);
        business.setDefaultDealValue(new BigDecimal("25.00"));
        // The fixture is a Nairobi café, so it is denominated in shillings and
        // keeps Nairobi hours. Leaving the schema default here would put the
        // demo's revenue figures under a currency symbol that contradicts its
        // own phone numbers.
        business.setCurrencyCode("KES");
        business.setTimezone("Africa/Nairobi");
        // Raised from the schema default of 100 because this fixture carries a
        // backlog: several hundred customers' worth of quiet regulars, plus the
        // offers the previous weeks already sent, all land inside one 30-day
        // window. Left at the default the demo's own data trips the demo's own
        // ceiling, and a third of the flagged list is suppressed before anyone
        // has configured anything — which reads as the product failing rather
        // than as a setting doing its job. The ceiling stays demonstrable: lower
        // it in Trigger settings and the next scan starts suppressing.
        business.setOfferCapPerWindow(250);
        businesses.save(business);

        AdminUser admin = new AdminUser();
        admin.setBusiness(business);
        admin.setEmail(adminEmail);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        adminUsers.save(admin);

        Instant now = Instant.now();
        random = new Random(CROWD_SEED);

        int transactionCount = seedCurrentCustomers(business, now);
        transactionCount += seedHistory(business, now);
        int crowd = seedCrowd(business, now);
        transactionCount += crowd;
        transactionCount += seedCrowdHistory(business, now);

        // The admin's ID, not their address: the seed email comes from config and
        // in a non-demo deploy that is a real operator's inbox (NFR-4).
        log.info("Seeded demo data: businessId={} customers={} transactions={} historicalFlags={} adminUserId={}",
                business.getId(),
                PROFILES.length + HISTORY.length + CROWD_SIZE + CROWD_HISTORY_SIZE,
                transactionCount, HISTORY.length + CROWD_HISTORY_SIZE, admin.getId());

        runInitialScan(business.getId());
    }

    /**
     * Runs the scan the schedule would have run at 02:00, so the demo has flags
     * and delivered offers without waiting a day or pressing a button.
     *
     * <p>The historical offers are already {@code SENT}, and the dispatcher only
     * picks up {@code PENDING} and {@code FAILED}, so this mails the customers
     * who just flagged and re-mails nobody.
     *
     * <p>A failure here is logged and swallowed. The seeded data is sound
     * whether or not a mail server happened to be listening, and an offer that
     * could not be delivered is left {@code FAILED} — retriable, so the next
     * scan picks it up rather than the whole demo failing to start.
     */
    private void runInitialScan(UUID businessId) {
        try {
            RetentionScanService.ScanSummary summary = retentionScan.scan(businessId);
            log.info("Seed scan complete: businessId={} flagged={} skipped={} errored={}",
                    businessId, summary.flagged(), summary.skipped(), summary.errored());
        } catch (RuntimeException e) {
            log.error("Seed scan failed; demo data is seeded but unflagged: businessId={}",
                    businessId, e);
        }
    }

    /** Customers whose current state the next scan will act on. */
    private int seedCurrentCustomers(Business business, Instant now) {
        int count = 0;
        for (Profile profile : PROFILES) {
            for (int visit = 0; visit < profile.visits(); visit++) {
                // Oldest first, ending `quietDays` ago.
                int daysAgo = profile.quietDays()
                        + profile.cadenceDays() * (profile.visits() - 1 - visit);
                ingest(business, profile.ref(), profile.name(),
                        "SEED-" + profile.ref() + "-" + visit,
                        now.minus(daysAgo, ChronoUnit.DAYS),
                        amountFor(profile.spend()), profile.email(), profile.phone());
                count++;
            }
        }
        return count;
    }

    /**
     * Customers flagged in previous weeks, most of whom returned.
     *
     * <p>Visits are ingested first so the flag is written against a customer
     * who already has cadence — and so ingestion has no open flag to
     * auto-resolve, leaving the historical {@code resolved_at} intact rather
     * than stamping it with the current time.
     */
    private int seedHistory(Business business, Instant now) {
        int count = 0;
        for (History entry : HISTORY) {
            Instant flaggedAt = now.minus(entry.flaggedWeeksAgo() * 7L, ChronoUnit.DAYS);
            // Six visits on cadence, finishing just over threshold before the flag.
            int visits = 6;
            BigDecimal cadence = BigDecimal.valueOf(entry.cadenceDays());
            Instant lastVisitBeforeFlag = flaggedAt.minus(
                    (long) Math.ceil(entry.cadenceDays() * 1.5) + 1, ChronoUnit.DAYS);

            for (int visit = visits - 1; visit >= 0; visit--) {
                ingest(business, entry.ref(), entry.name(),
                        "SEED-H-" + entry.ref() + "-" + visit,
                        lastVisitBeforeFlag.minus(entry.cadenceDays() * (long) visit, ChronoUnit.DAYS),
                        amountFor(entry.spend()), entry.email(), entry.phone());
                count++;
            }

            if (entry.recoveredAfterDays() != null) {
                // The return visit itself, before the flag exists.
                ingest(business, entry.ref(), entry.name(),
                        "SEED-H-" + entry.ref() + "-return",
                        flaggedAt.plus(entry.recoveredAfterDays(), ChronoUnit.DAYS),
                        amountFor(entry.spend()), entry.email(), entry.phone());
                count++;
            }

            Customer customer = customers
                    .findByBusinessIdAndExternalRef(business.getId(), entry.ref())
                    .orElseThrow();
            writeHistoricalFlag(business, customer, entry.recoveredAfterDays(), flaggedAt, cadence);
        }
        return count;
    }

    // --- the generated crowd ---------------------------------------------------

    /**
     * The customer base the named profiles sit inside.
     *
     * <p>Each generated customer's rhythm is decided first and their dates are
     * built from it, rather than the other way round. The trigger compares the
     * gap since the last visit against the average of the gaps before it, and
     * the average is computed by the same code that will compute it at scan
     * time — so a customer is placed quiet or on-rhythm relative to the average
     * their own visits will actually produce, not to the cadence they were
     * asked for. Working the other way round would leave a slice of the crowd
     * flagging or not flagging by a rounding accident, which is exactly the
     * kind of thing that makes a demo look broken rather than busy.
     */
    private int seedCrowd(Business business, Instant now) {
        int count = 0;
        for (int i = 0; i < CROWD_SIZE; i++) {
            String ref = "POS-5" + String.format("%03d", i);
            int cadenceDays = CADENCES[random.nextInt(CADENCES.length)];
            // A few one-and-two-visit customers on purpose: below
            // MIN_TRANSACTIONS there is no rhythm, so they land in the report's
            // "no rhythm yet" band — a data gap rather than a retention problem,
            // and one the demo should be able to show.
            int visits = i % 25 == 0 ? 1 + random.nextInt(2) : 4 + random.nextInt(9);

            double[] gaps = jitteredGaps(cadenceDays, visits);
            double average = mean(gaps);
            double threshold = clamp(average * 1.5);
            boolean quiet = random.nextInt(100) < CROWD_QUIET_PERCENT;
            double quietDays = quiet
                    ? threshold + 3 + random.nextDouble() * threshold * 0.8
                    : random.nextDouble() * threshold * 0.8;

            Instant lastVisit = now.minus((long) quietDays, ChronoUnit.DAYS)
                    .minus(Math.round(quietDays % 1 * 24), ChronoUnit.HOURS);
            int spend = spendFor();

            List<Instant> times = visitTimes(lastVisit, gaps);
            for (int v = 0; v < times.size(); v++) {
                ingest(business, ref, name(), "SEED-C-" + ref + "-" + v, times.get(v),
                        amountFor(spend), contactFor(ref, "email"), contactFor(ref, "phone"));
                count++;
            }
        }
        return count;
    }

    /**
     * A flagged cohort from previous weeks, so the programme has a record.
     *
     * <p>Spread over the previous eleven weeks rather than one, because the
     * dashboard's trend and the recovery window both need history behind them
     * to say anything. Roughly {@link #CROWD_RECOVERY_PERCENT} came back, and
     * the rest are still open — which is what a real flagged list looks like.
     */
    private int seedCrowdHistory(Business business, Instant now) {
        int count = 0;
        for (int i = 0; i < CROWD_HISTORY_SIZE; i++) {
            String ref = "POS-6" + String.format("%03d", i);
            int cadenceDays = CADENCES[random.nextInt(CADENCES.length)];
            Instant flaggedAt = now.minus((1 + random.nextInt(11)) * 7L, ChronoUnit.DAYS);

            double[] gaps = jitteredGaps(cadenceDays, 6);
            double average = mean(gaps);
            BigDecimal cadence = BigDecimal.valueOf(average).setScale(2, RoundingMode.HALF_UP);

            // The last visit lands just past their own threshold, which is the
            // state that raised the flag at the time.
            double daysQuietBeforeFlag = clamp(average * 1.5) + 1;
            Instant lastVisitBeforeFlag = flaggedAt.minus(
                    (long) daysQuietBeforeFlag, ChronoUnit.DAYS);
            int spend = spendFor();

            List<Instant> times = visitTimes(lastVisitBeforeFlag, gaps);
            for (int v = 0; v < times.size(); v++) {
                ingest(business, ref, name(), "SEED-CH-" + ref + "-" + v, times.get(v),
                        amountFor(spend), contactFor(ref, "email"), contactFor(ref, "phone"));
                count++;
            }

            // Inside the recovery window, or the return would not count as one:
            // the window is 14 days by default and the dashboard's money is
            // measured inside it.
            Integer recoveredAfterDays = random.nextInt(100) < CROWD_RECOVERY_PERCENT
                    ? 2 + random.nextInt(12)
                    : null;

            if (recoveredAfterDays != null) {
                ingest(business, ref, name(), "SEED-CH-" + ref + "-return",
                        flaggedAt.plus(recoveredAfterDays, ChronoUnit.DAYS), amountFor(spend),
                        contactFor(ref, "email"), contactFor(ref, "phone"));
                count++;
            }

            Customer customer = customers
                    .findByBusinessIdAndExternalRef(business.getId(), ref)
                    .orElseThrow();
            writeHistoricalFlag(business, customer, recoveredAfterDays, flaggedAt, cadence);
        }
        return count;
    }

    private void writeHistoricalFlag(Business business, Customer customer,
                                     Integer recoveredAfterDays, Instant flaggedAt,
                                     BigDecimal cadence) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setFlaggedAt(flaggedAt);
        flag.setAvgIntervalDaysAtFlag(cadence);
        flag.setThresholdDaysApplied(business.thresholdDaysFor(cadence));
        if (recoveredAfterDays != null) {
            flag.resolve(flaggedAt.plus(recoveredAfterDays, ChronoUnit.DAYS));
        }
        flags.save(flag);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(business.getDefaultDealValue());
        offer.setRedemptionCode(redemptionCodes.generate());
        offer.setCreatedAt(flaggedAt);
        offer.setUpdatedAt(flaggedAt);
        // Historical offers were delivered at the time; the dispatcher only
        // picks up PENDING and FAILED, so these stay untouched by later runs.
        offer.markSent(flaggedAt.plus(2, ChronoUnit.MINUTES));
        offers.save(offer);
    }

    /**
     * Gaps around the customer's rhythm, each within a fifth of it.
     *
     * <p>Nobody visits every Tuesday exactly. Even gaps would give every
     * customer in a band the same average interval, which would show up as a
     * histogram with spikes in it rather than the spread a real till produces.
     */
    private double[] jitteredGaps(int cadenceDays, int visits) {
        double[] gaps = new double[Math.max(0, visits - 1)];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = Math.max(1, cadenceDays * (0.8 + random.nextDouble() * 0.4));
        }
        return gaps;
    }

    /**
     * Visit timestamps, oldest first, ending at {@code lastVisit}.
     *
     * <p>The fractional part of a gap is spent as hours rather than dropped, so
     * a customer whose gaps average 7.4 days actually has gaps averaging 7.4
     * days. Truncating would pull every generated average back towards whole
     * days and, with it, every threshold the trigger derives from them.
     */
    private static List<Instant> visitTimes(Instant lastVisit, double[] gaps) {
        List<Instant> times = new ArrayList<>(gaps.length + 1);
        Instant cursor = lastVisit;
        times.add(cursor);
        for (int i = gaps.length - 1; i >= 0; i--) {
            cursor = cursor.minus((long) gaps[i], ChronoUnit.DAYS)
                    .minus(Math.round(gaps[i] % 1 * 24), ChronoUnit.HOURS);
            times.add(cursor);
        }
        Collections.reverse(times);
        return times;
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return total / values.length;
    }

    /** The trigger's own clamp, so the fixture is placed against the real one. */
    private static double clamp(double threshold) {
        return Math.min(60, Math.max(3, threshold));
    }

    /** A basket near this customer's usual spend, on the ten. */
    private BigDecimal amountFor(int base) {
        double varied = base * (0.75 + random.nextDouble() * 0.5);
        long rounded = Math.max(50, Math.round(varied / 10.0) * 10);
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP);
    }

    /** A typical spend, drawn from the bands that make a café's takings lopsided. */
    private int spendFor() {
        int total = 0;
        for (SpendBand band : SPEND_BANDS) {
            total += band.weight();
        }
        int draw = random.nextInt(total);
        for (SpendBand band : SPEND_BANDS) {
            draw -= band.weight();
            if (draw < 0) {
                return band.min() + random.nextInt(band.max() - band.min());
            }
        }
        return SPEND_BANDS[0].min();
    }

    private String name() {
        return GIVEN_NAMES[random.nextInt(GIVEN_NAMES.length)] + " "
                + FAMILY_NAMES[random.nextInt(FAMILY_NAMES.length)];
    }

    /**
     * A contact detail, or nothing.
     *
     * <p>Derived from the reference rather than drawn, so the same customer
     * carries the same address on every visit and a replayed demo does not
     * rewrite who is reachable. One in twenty has no email and one in twelve no
     * phone, which is what makes the uncontactable path (FR-5) show up in a
     * crowd rather than only in the named profiles.
     */
    private String contactFor(String ref, String kind) {
        int n = Integer.parseInt(ref.substring(ref.length() - 3));
        if (kind.equals("email")) {
            return n % 20 == 7 ? null : ref.toLowerCase() + "@example.test";
        }
        return n % 12 == 5 ? null : "+2547" + String.format("%08d", 10000000 + n * 137 % 89999999);
    }

    /**
     * @param customerName null for a couple of profiles on purpose. A POS export
     *                     that carries only a reference is a real shape of data,
     *                     and keeping some in the demo means the dashboard's
     *                     no-name path and the offer email's greeting fallback
     *                     are both exercised rather than just described.
     */
    private void ingest(Business business, String customerRef, String customerName,
                        String externalTxnId, Instant occurredAt, BigDecimal amount,
                        String email, String phone) {
        ingestion.ingest(business.getId(), new TransactionIngestRequest(
                business.getId(), customerRef, customerName, null, externalTxnId,
                amount, occurredAt, email, phone));
    }
}
