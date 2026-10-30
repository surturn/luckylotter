package com.lucklotter.config;

import com.lucklotter.domain.BusinessType;
import com.lucklotter.domain.DealType;
import com.lucklotter.service.ProvisioningService;
import com.lucklotter.service.ValidationException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * The operator command line: {@code create-business}, {@code reset-password},
 * {@code deactivate-admin}. Active only under the {@code operator} profile,
 * which also turns off the web server, the scheduled scan and the demo seeder,
 * so running a command can never start a second copy of the service.
 *
 * <p>Results go to stdout deliberately, not through the logger: a temporary
 * password is a secret, and the log stream is retained and shipped.
 *
 * <pre>
 * docker compose run --rm backend --spring.profiles.active=operator \
 *     create-business --name="Kaldi Coffee" --admin-email=owner@example.com \
 *     --currency=KES --timezone=Africa/Nairobi --deal-value=15
 * </pre>
 */
@Component
@Profile("operator")
public class OperatorCommandRunner implements ApplicationRunner {

    private static final String USAGE = """
            Commands:
              create-business --name= --type= --admin-email= --currency= --timezone= --deal-value=
                              [--deal-type=PERCENT_OFF|FIXED_AMOUNT_OFF|FREE_ITEM] [--reply-to=]
                              --type is one of: cafe-restaurant, barbershop, hair-salon,
                              spa-wellness, other. It sets the starting settings and wording.
              reset-password   --email=
              deactivate-admin --email=
            """;

    private final ProvisioningService provisioning;
    private final ApplicationContext context;

    public OperatorCommandRunner(ProvisioningService provisioning, ApplicationContext context) {
        this.provisioning = provisioning;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute(args);
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    int execute(ApplicationArguments args) {
        List<String> commands = args.getNonOptionArgs();
        if (commands.size() != 1) {
            System.err.println(USAGE);
            return 2;
        }
        try {
            switch (commands.get(0)) {
                case "create-business" -> {
                    var result = provisioning.createBusiness(new ProvisioningService.NewBusiness(
                            option(args, "name"),
                            businessType(option(args, "type")),
                            option(args, "admin-email"),
                            option(args, "currency"),
                            option(args, "timezone"),
                            dealType(option(args, "deal-type")),
                            decimal(option(args, "deal-value")),
                            option(args, "reply-to")));
                    System.out.println("Business created.");
                    System.out.println("  businessId:         " + result.businessId());
                    System.out.println("  admin email:        " + result.adminEmail());
                    System.out.println("  temporary password: " + result.temporaryPassword());
                    System.out.println("Shown once. The admin must change it at first sign-in.");
                }
                case "reset-password" -> {
                    var result = provisioning.resetPassword(option(args, "email"));
                    System.out.println("Password reset for " + result.adminEmail() + ".");
                    System.out.println("  temporary password: " + result.temporaryPassword());
                    System.out.println("Shown once. All existing sessions have ended.");
                }
                case "deactivate-admin" -> {
                    provisioning.deactivateAdmin(option(args, "email"));
                    System.out.println("Admin deactivated. Their session ends on its next request.");
                }
                default -> {
                    System.err.println("Unknown command: " + commands.get(0));
                    System.err.println(USAGE);
                    return 2;
                }
            }
            return 0;
        } catch (ValidationException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static String option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static BusinessType businessType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return BusinessType.parse(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("type must be one of: " + ProvisioningService.validTypes());
        }
    }

    private static DealType dealType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DealType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("deal-type must be PERCENT_OFF, FIXED_AMOUNT_OFF or FREE_ITEM");
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException("deal-value must be a number");
        }
    }
}
