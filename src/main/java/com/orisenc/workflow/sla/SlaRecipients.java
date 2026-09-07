package com.orisenc.workflow.sla;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who hears about a crossed rung.
 *
 * <p>The audience widens with the stage, and that widening is the whole point of an escalation
 * ladder: a warning is the owner's business, a breach is the owner's and the requester's, and an
 * item still open well past its deadline is somebody else's problem to solve.
 *
 * <pre>
 *   DUE_SOON   owner (and anybody standing in for them), or the creator when nobody has claimed it
 *   BREACHED   owner, their delegates, and the creator
 *   ESCALATED  the above, plus the parent item's owner and the configured escalation mailbox
 * </pre>
 *
 * <p><b>A delegate hears whatever the owner hears.</b> Chasing somebody who is on leave while the
 * colleague covering for them is told nothing is an escalation delivered to the one person who
 * cannot act on it - and it climbs the ladder anyway, so by the time anybody who can act finds out,
 * it is a breach rather than a warning.
 *
 * <p><b>An unclaimed item warns its creator.</b> Sending a due-soon warning to nobody is the failure
 * mode worth naming: the item most likely to be missed is exactly the one no-one has picked up, and
 * a queue that stays silent about it is worse than one that never warned at all.
 *
 * <p>Pure and static, like {@link SlaLadder}, because "who gets told" is the half of this feature
 * that is easy to get quietly wrong and cheap to test exhaustively.
 */
public final class SlaRecipients {

  private SlaRecipients() {}

  /**
   * The recipients for one rung, in the order they were added and with no duplicates.
   *
   * <p>Deduplication is case-insensitive because these are UPNs, which the platform compares
   * case-insensitively everywhere else. Without it, an item somebody raised and then claimed
   * themselves would send that person the same breach twice - once as owner, once as creator - and
   * the Integration Service's idempotency would not catch it, because its key includes the recipient
   * exactly as written.
   */
  public static List<String> forStage(SlaCandidate candidate, SlaStage stage, String configuredContact) {
    return forStage(candidate, stage, configuredContact, List.of());
  }

  /**
   * @param delegates whoever currently stands in for the owner on this item, from the delegation
   *     service. Empty when nobody does, which is the ordinary case.
   */
  public static List<String> forStage(SlaCandidate candidate, SlaStage stage, String configuredContact,
      List<String> delegates) {
    var seen = new LinkedHashSet<String>();
    var recipients = new ArrayList<String>();

    switch (stage) {
      case DUE_SOON -> {
        add(recipients, seen, candidate.owner() != null ? candidate.owner() : candidate.creator());
        addAll(recipients, seen, delegates);
      }
      case BREACHED -> {
        add(recipients, seen, candidate.owner());
        addAll(recipients, seen, delegates);
        add(recipients, seen, candidate.creator());
      }
      case ESCALATED -> {
        add(recipients, seen, candidate.owner());
        addAll(recipients, seen, delegates);
        add(recipients, seen, candidate.creator());
        add(recipients, seen, candidate.escalationContact());
        add(recipients, seen, configuredContact);
      }
    }
    return List.copyOf(recipients);
  }

  private static void addAll(List<String> recipients, Set<String> seen, List<String> candidates) {
    if (candidates == null) return;
    for (String candidate : candidates) add(recipients, seen, candidate);
  }

  private static void add(List<String> recipients, Set<String> seen, String recipient) {
    if (recipient == null || recipient.isBlank()) return;
    String trimmed = recipient.trim();
    if (seen.add(trimmed.toLowerCase(Locale.ROOT))) recipients.add(trimmed);
  }
}
