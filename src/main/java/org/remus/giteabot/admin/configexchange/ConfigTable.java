package org.remus.giteabot.admin.configexchange;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One importable configuration table.
 *
 * <p>Instances are declared in {@link ConfigExchangeRegistry}, which is the only
 * place that knows which entity types the configuration document may contain and in
 * which order they are applied. Everything else in this package works generically over
 * this description, so a new field on an entity needs no code here at all.</p>
 *
 * @param jsonKey      section name in the document (camelCase of the table)
 * @param type         entity class the section deserializes into
 * @param name         unique business key of a row, used for lookups and skip
 * @param readAll      all rows, for export
 * @param findByName   unique-name lookup; {@code null} when the table has none
 * @param save         persists through the owning service, so validation and
 *                     encryption still apply; {@code null} means the importer has a
 *                     dedicated path for this type (only {@code adminUsers}: the
 *                     plaintext password must be hashed, not stored)
 * @param exportSecrets patches decrypted credentials into the exported node;
 *                     {@code null} when the row has no credentials
 * @param validate     import-side guard, or {@code null}
 */
public record ConfigTable<T>(
        String jsonKey,
        Class<T> type,
        Function<T, String> name,
        Supplier<List<T>> readAll,
        Function<String, Optional<T>> findByName,
        Consumer<T> save,
        BiConsumer<T, ObjectNode> exportSecrets,
        Consumer<T> validate) {
}
