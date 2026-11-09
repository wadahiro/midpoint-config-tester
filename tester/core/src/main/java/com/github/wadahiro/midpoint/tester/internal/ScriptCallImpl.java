package com.github.wadahiro.midpoint.tester.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

import com.github.wadahiro.midpoint.tester.Answer;
import com.github.wadahiro.midpoint.tester.Run;
import com.github.wadahiro.midpoint.tester.ScriptCall;

/** {@link ScriptCall}: evaluated by the line's drivers. */
final class ScriptCallImpl implements ScriptCall {

    private final MidPointImpl mp;
    private final String code;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final Map<String, Class<?>> types = new LinkedHashMap<>();
    private String language;
    private QName returnType;

    ScriptCallImpl(MidPointImpl mp, String code) {
        this.mp = mp;
        this.code = code;
    }

    @Override
    public ScriptCall variable(String name, Object value) {
        variables.put(name, value);
        return this;
    }

    @Override
    public ScriptCall variable(String name, Object value, Class<?> type) {
        variables.put(name, value);
        types.put(name, type);
        return this;
    }

    @Override
    public ScriptCall language(String languageUri) {
        this.language = languageUri;
        return this;
    }

    @Override
    public ScriptCall returns(QName type) {
        this.returnType = type;
        return this;
    }

    @Override
    public Answer<List<Object>> evaluate() {
        List<Object> output = new ArrayList<>();
        Run run = mp.run("script " + abbreviated(), (task, result) -> {
            output.addAll(mp.drivers().evaluateScript(language, code, variables, types, returnType, task, result));
            return MidPointImpl.Outcome.NONE;
        }, true);
        return new Answer<>(output, run);
    }

    private String abbreviated() {
        String line = code.strip().replaceAll("\\s+", " ");
        return line.length() > 60 ? line.substring(0, 57) + "..." : line;
    }
}
