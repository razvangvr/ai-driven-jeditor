package dev.jeditor.compiler;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the thing a piece of code refers to is declared.
 *
 * @param name   what was resolved: a fully qualified type name, or the simple name of a member or variable
 * @param file   the source file containing the declaration; empty when only compiled code is
 *               available (JDK classes, jars), so there is nothing to open
 * @param offset 0-based offset of the declared name within {@code file}; -1 when {@code file} is empty
 */
public record DeclarationTarget(String name, Optional<Path> file, int offset) {

    public boolean hasSource() {
        return file.isPresent();
    }
}
