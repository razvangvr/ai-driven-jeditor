package dev.jeditor.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindDeclarationTest {

    private static final String MONEY = """
            package com.acme.util;

            /** Money, in cents. */
            @Deprecated
            public final class Money {
                public static final Money ZERO = new Money(0);
                private final long cents;

                public Money(long cents) { this.cents = cents; }

                public Money plus(Money other) { return new Money(cents + other.cents); }
                public Money plus(long more) { return new Money(cents + more); }

                public static class Parser {
                    public static Money parse(String text) { return ZERO; }
                }
            }
            """;

    private static final String ACCOUNT = """
            package com.acme.bank;

            import com.acme.util.Money;
            import java.util.List;

            public class Account extends Base {
                private Money balance = Money.ZERO;
                private List<Money> history;
                private Helper helper = new Helper();

                void deposit(Money amount) {
                    Money updated = balance.plus(amount);
                    balance = updated.plus(5L);
                    String text = describe();
                    com.acme.util.Money.Parser.parse(text);
                    inherited();
                    Runnable r = this::describe;
                    Unknown broken = null;
                }

                String describe() { return "x"; }
            }

            class Helper {}
            """;

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @TempDir
    Path dir;

    private Path money;
    private Path base;
    private Path account;

    @BeforeEach
    void project() throws IOException {
        money = write("src/com/acme/util/Money.java", MONEY);
        base = write("src/com/acme/bank/Base.java", "package com.acme.bank;\nclass Base { void inherited() {} }\n");
        account = write("src/com/acme/bank/Account.java", ACCOUNT);
    }

    // ---- types --------------------------------------------------------------------------------

    @Test
    void typeInAFieldDeclarationOpensItsSourceFile() {
        DeclarationTarget target = at("private Money balance", "Money");

        assertEquals("com.acme.util.Money", target.name());
        assertEquals(Optional.of(money), target.file());
        assertEquals(MONEY.indexOf("Money {"), target.offset(), "lands on the class name, past javadoc, annotations and modifiers");
    }

    @Test
    void typeIsFoundFromEveryKindOfUse() {
        for (String context : new String[] {"import com.acme.util.Money", "List<Money>", "deposit(Money amount", "Money updated"}) {
            DeclarationTarget target = at(context, "Money");
            assertEquals(Optional.of(money), target.file(), context);
            assertEquals(MONEY.indexOf("Money {"), target.offset(), context);
        }
    }

    @Test
    void anyPositionInsideTheNameWorksIncludingJustAfterIt() {
        int start = ACCOUNT.indexOf("Money balance");
        for (int offset = start; offset <= start + "Money".length(); offset++) {
            assertEquals("com.acme.util.Money", find(offset).orElseThrow().name(), "offset " + offset);
        }
    }

    @Test
    void superclassInAnotherFile() {
        DeclarationTarget target = at("extends Base", "Base");
        assertEquals(Optional.of(base), target.file());
        assertEquals(indexIn(base, "Base {"), target.offset());
    }

    @Test
    void secondaryTypeInTheSameFile() {
        DeclarationTarget target = at("new Helper()", "Helper");
        assertEquals("com.acme.bank.Helper", target.name());
        assertEquals(Optional.of(account), target.file());
        assertEquals(ACCOUNT.indexOf("Helper {}"), target.offset());
    }

    @Test
    void nestedTypeThroughAQualifiedName() {
        DeclarationTarget target = at("com.acme.util.Money.Parser.parse", "Parser");
        assertEquals("com.acme.util.Money.Parser", target.name());
        assertEquals(MONEY.indexOf("Parser {"), target.offset());

        // the middle of the same qualified name is the outer class
        assertEquals("com.acme.util.Money", at("com.acme.util.Money.Parser.parse", "Money").name());
    }

    @Test
    void jdkTypesResolveButHaveNoSourceToOpen() {
        DeclarationTarget target = at("String text", "String");
        assertEquals("java.lang.String", target.name());
        assertFalse(target.hasSource());
        assertEquals(-1, target.offset());

        assertEquals("java.util.List", at("List<Money>", "List").name());
    }

    // ---- members and variables ----------------------------------------------------------------

    @Test
    void methodCallPicksTheRightOverload() {
        DeclarationTarget byMoney = at("balance.plus(amount)", "plus");
        assertEquals(MONEY.indexOf("plus(Money other)"), byMoney.offset());

        DeclarationTarget byLong = at("updated.plus(5L)", "plus");
        assertEquals(MONEY.indexOf("plus(long more)"), byLong.offset());
        assertEquals("plus", byLong.name());
    }

    @Test
    void staticFieldInAnotherFile() {
        DeclarationTarget target = at("Money.ZERO", "ZERO");
        assertEquals(Optional.of(money), target.file());
        assertEquals(MONEY.indexOf("ZERO ="), target.offset());
    }

    @Test
    void methodInTheSameFileAndInheritedMethod() {
        assertEquals(ACCOUNT.indexOf("describe() {"), at("text = describe()", "describe").offset());
        assertEquals(ACCOUNT.indexOf("describe() {"), at("this::describe", "describe").offset());

        DeclarationTarget inherited = at("inherited();", "inherited");
        assertEquals(Optional.of(base), inherited.file());
    }

    @Test
    void fieldParameterAndLocalVariable() {
        assertEquals(ACCOUNT.indexOf("balance = Money.ZERO"), at("balance.plus(amount)", "balance").offset());
        assertEquals(ACCOUNT.indexOf("amount) {"), at("plus(amount)", "amount").offset());
        assertEquals(ACCOUNT.indexOf("updated = balance"), at("updated.plus(5L)", "updated").offset());
    }

    // ---- nothing to navigate to ---------------------------------------------------------------

    @Test
    void keywordsLiteralsWhitespaceAndUnresolvedNamesGiveNothing() {
        assertEquals(Optional.empty(), find(ACCOUNT.indexOf("private Money") + 2));   // keyword
        assertEquals(Optional.empty(), find(ACCOUNT.indexOf("\"x\"") + 1));           // string literal
        assertEquals(Optional.empty(), find(ACCOUNT.indexOf("5L")));                  // number
        assertEquals(Optional.empty(), find(ACCOUNT.indexOf("\n\n") + 1));            // blank line
        assertEquals(Optional.empty(), find(ACCOUNT.indexOf("Unknown") + 2));         // does not compile
        assertEquals(Optional.empty(), find(ACCOUNT.indexOf("this::")));              // 'this'
        assertEquals(Optional.empty(), find(ACCOUNT.indexOf("com.acme.util.Money.Parser") + 5)); // package
    }

    @Test
    void outOfRangeOffsetsAndMissingCompilerGiveNothing() {
        assertEquals(Optional.empty(), find(-1));
        assertEquals(Optional.empty(), find(ACCOUNT.length() + 1));
        assertEquals(Optional.empty(),
                new JavaSourceAnalyzer(null).findDeclaration(account, ACCOUNT, ACCOUNT.indexOf("Money balance")));
    }

    @Test
    void worksOnUnsavedContentAndDespiteErrorsElsewhere() {
        String edited = ACCOUNT.replace("private Helper helper", "int oops = ;\n    private Money extra;\n    private Helper helper");
        DeclarationTarget target = analyzer.findDeclaration(account, edited, edited.indexOf("Money extra")).orElseThrow();
        assertEquals(Optional.of(money), target.file());

        DeclarationTarget local = analyzer.findDeclaration(account, edited, edited.indexOf("Helper helper")).orElseThrow();
        assertEquals(edited.indexOf("Helper {}"), local.offset(), "offsets refer to the edited text, not the file on disk");
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Resolves {@code name} where it occurs inside the first occurrence of {@code context} in Account.java. */
    private DeclarationTarget at(String context, String name) {
        int contextStart = ACCOUNT.indexOf(context);
        assertTrue(contextStart >= 0, context);
        int offset = contextStart + context.lastIndexOf(name) + 1;
        return find(offset).orElseThrow(() -> new AssertionError("nothing found for " + name + " in '" + context + "'"));
    }

    private Optional<DeclarationTarget> find(int offset) {
        return analyzer.findDeclaration(account, ACCOUNT, offset);
    }

    private static int indexIn(Path file, String text) {
        try {
            return Files.readString(file).indexOf(text);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
