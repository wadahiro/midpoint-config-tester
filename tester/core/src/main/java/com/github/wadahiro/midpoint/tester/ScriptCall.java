package com.github.wadahiro.midpoint.tester;

import java.util.List;
import javax.xml.namespace.QName;

/**
 * An ad-hoc script run through midPoint's production {@code ExpressionFactory} and {@code ScriptExpressionFactory}
 * ({@link MidPoint#script}): the real {@code midpoint}, {@code basic} and {@code log} function libraries and every
 * custom function library in the repository ({@code myLib.execute('fn', [arg: value])}), with the full expression
 * profile.
 *
 * <p><b>This is the only driver whose variables are given by the test</b>, not by midPoint: use it to try library
 * functions and snippets, not to stand in for a mapping or a hook (drive midPoint so that it evaluates those).</p>
 *
 * <pre>{@code
 * Answer<List<Object>> out = mp.script("custom.execute('initials', [name: who])")
 *         .variable("who", "John Doe")
 *         .evaluate();
 * assertThat(out.value()).containsExactly("JD");
 * Evaluation function = out.run().select().owner("custom").single();   // the library function, nested
 * }</pre>
 */
public interface ScriptCall {

    /** A variable as the script sees it; objects ({@code UserType}, {@code PrismObject}) keep their definition. */
    ScriptCall variable(String name, Object value);

    /** A variable of a declared type (e.g. for null values). */
    ScriptCall variable(String name, Object value, Class<?> type);

    /** The script language URI; Groovy by default. */
    ScriptCall language(String languageUri);

    /** The type of the output values (XSD or midPoint type QName); {@code xsd:string} by default. */
    ScriptCall returns(QName type);

    /** Evaluates the script; its output is the non-negative values (zero + plus). Throws {@link OperationFailed}. */
    Answer<List<Object>> evaluate();
}
