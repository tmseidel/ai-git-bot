package org.remus.giteabot.review.structured;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserves the next number of a public-reference namespace. Allocation always happens in
 * the transaction that creates the referenced row, so a rolled-back creation also
 * releases its number and a caller can never advertise an unreserved reference.
 */
@Service
@RequiredArgsConstructor
public class ReferenceAllocator {

    private final ReferenceSequenceRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(ReferenceNamespace namespace) {
        ReferenceSequence sequence = repository.findForUpdate(namespace.key())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing reference sequence row '" + namespace.key()
                                + "'; the seeding migration did not run"));
        long value = sequence.getNextValue();
        sequence.setNextValue(value + 1);
        return namespace.prefix() + value;
    }
}
