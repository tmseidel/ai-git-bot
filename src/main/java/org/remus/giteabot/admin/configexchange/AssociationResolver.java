package org.remus.giteabot.admin.configexchange;

import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Turns the reference objects embedded in the document into managed entities.
 *
 * <p>The document may contain a full nested object or only {@code {"name": …}}; both
 * deserialize into an entity instance whose identity is irrelevant. Resolution
 * replaces that instance with the row the name refers to, which is either a Flyway
 * seed ({@code Default} system prompt, {@code Issue: Coding Agent} configuration) or a
 * row imported earlier in the same document because the registry orders tables by
 * dependency.</p>
 *
 * <p>A name that resolves to nothing is an error, not a silent {@code null}: a bot
 * pointing at a missing integration would boot and then fail on its first webhook.</p>
 */
final class AssociationResolver {

    private AssociationResolver() {
    }

    static void resolve(Object entity, Map<Class<?>, Function<String, Optional<Object>>> lookups) {
        for (Field field : entity.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(ManyToOne.class)) {
                continue;
            }
            Object reference = EntityIdentity.read(entity, field);
            if (reference == null) {
                continue;
            }
            String name = nameOf(reference);
            Function<String, Optional<Object>> lookup = lookups.get(field.getType());
            if (lookup == null) {
                throw new IllegalArgumentException("Cannot resolve '" + field.getName()
                        + "': " + field.getType().getSimpleName() + " is not importable");
            }
            Object resolved = lookup.apply(name).orElseThrow(() -> new IllegalArgumentException(
                    "'" + field.getName() + "' references " + field.getType().getSimpleName()
                            + " '" + name + "', which the document does not declare"));
            EntityIdentity.write(entity, field, resolved);
        }
    }

    /**
     * Points every child of a {@code @OneToMany} collection back at its parent.
     *
     * <p>Hibernate can usually infer this from the collection on persist, but relying
     * on that makes the importer depend on flush internals; setting the owning side
     * explicitly is what the services themselves do (see
     * {@code WorkflowConfigurationService.cloneConfiguration}).</p>
     */
    static void linkChildren(Object parent) {
        for (Field field : parent.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(OneToMany.class)) {
                continue;
            }
            if (EntityIdentity.read(parent, field) instanceof Collection<?> children) {
                for (Object child : children) {
                    if (child == null) {
                        continue;
                    }
                    linkParent(child, parent);
                    linkChildren(child);
                }
            }
        }
    }

    /** Points one child's owning {@code @ManyToOne} at its parent. */
    static void linkParent(Object child, Object parent) {
        for (Field childField : child.getClass().getDeclaredFields()) {
            if (!childField.isAnnotationPresent(ManyToOne.class)
                    || Modifier.isStatic(childField.getModifiers())
                    || !childField.getType().isInstance(parent)) {
                continue;
            }
            EntityIdentity.write(child, childField, parent);
        }
    }

    /** Business key of a referenced row; every importable reference target has {@code getName()}. */
    static String nameOf(Object reference) {
        try {
            Method getter = reference.getClass().getMethod("getName");
            return (String) getter.invoke(reference);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Referenced "
                    + reference.getClass().getSimpleName() + " has no name", e);
        }
    }
}
