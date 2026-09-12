package com.stripeclone.ledger;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Health check that asks whether the books balance.
 *
 * <p>A normal health check tells you the process is up and the database is reachable.
 * Neither of those says anything about whether the system is still correct. This one sums
 * every ledger entry and compares the balance projection against the entries it came from.
 *
 * <p>If either comes back wrong the answer is not "restart it". It means money has been
 * created or destroyed somewhere, and the right response is to stop writing and go read
 * the entries.
 *
 * <p>The cost is a full scan of the entries table, so this is the sort of thing you point a
 * slow monitor at rather than a per-second liveness probe.
 */
@Component("ledger")
public class LedgerHealthIndicator implements HealthIndicator {

    private final LedgerRepository repository;

    public LedgerHealthIndicator(LedgerRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        try {
            long entrySum = repository.sumAllEntries();
            List<String> drifted = repository.findDriftedAccounts();

            if (entrySum != 0) {
                return Health.down()
                        .withDetail("balanced", false)
                        .withDetail("entrySum", entrySum)
                        .withDetail("message",
                                "Ledger entries do not sum to zero. Money has been created "
                                        + "or destroyed; stop writes and inspect the entries.")
                        .build();
            }

            if (!drifted.isEmpty()) {
                return Health.down()
                        .withDetail("balanced", true)
                        .withDetail("driftedAccounts", drifted)
                        .withDetail("message",
                                "The balance projection disagrees with the entries. The "
                                        + "entries are authoritative; the projection can be "
                                        + "rebuilt from them.")
                        .build();
            }

            return Health.up()
                    .withDetail("balanced", true)
                    .withDetail("driftedAccounts", 0)
                    .build();

        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
