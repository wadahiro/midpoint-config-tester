package com.github.wadahiro.midpoint.configtester;

/**
 * The deployment of a manifest could not be loaded: the manifest is invalid, a file cannot be prepared (an unknown
 * property, a resource the tester cannot stand in for), or midPoint's post-initial import failed on a file. The
 * message names the file and shows the errors of midPoint's operation result; the whole result is written next to
 * the prepared copy ({@code <workDir>/tester-deployment/<name>/load-failure.txt}).
 */
public class DeploymentLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DeploymentLoadException(String message) {
        super(message);
    }

    public DeploymentLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
