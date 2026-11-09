package com.github.wadahiro.midpoint.tester.internal;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.github.wadahiro.midpoint.tester.Reports;

/**
 * {@link Reports} on 4.0: not supported. 4.0 has no object collection reports nor report import (they came with 4.4);
 * its reports are Jasper reports, whose libraries are not available (dead repositories) and whose scripts are not what
 * later lines run.
 */
final class ReportsImpl implements Reports {

    ReportsImpl(MidPointImpl mp) {
    }

    @Override
    public Output export(String report) {
        return export(report, Map.of());
    }

    @Override
    public Output export(String report, Map<String, Object> parameters) {
        throw new UnsupportedOperationException("midPoint 4.0 has no object collection reports (4.4+)");
    }

    @Override
    public Import importFrom(String report, List<String> lines) {
        throw new UnsupportedOperationException("midPoint 4.0 has no report import (4.4+)");
    }

    @Override
    public Import importFrom(String report, List<String> lines, String username) {
        throw new UnsupportedOperationException("midPoint 4.0 has no report import (4.4+)");
    }

    @Override
    public Import importFrom(String report, File csv) {
        throw new UnsupportedOperationException("midPoint 4.0 has no report import (4.4+)");
    }
}
