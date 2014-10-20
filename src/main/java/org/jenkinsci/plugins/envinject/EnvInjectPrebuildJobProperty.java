package org.jenkinsci.plugins.envinject;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.BuildListener;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.model.Node;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.jenkinsci.lib.envinject.EnvInjectLogger;
import org.jenkinsci.plugins.envinject.service.EnvInjectEnvVars;
import org.jenkinsci.plugins.envinject.service.EnvInjectVariableGetter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * @author Arcadiy Ivanov (arcivanov)
 * @author Gregory Boissinot
 */
public class EnvInjectPrebuildJobProperty<T extends Job<?, ?>> extends JobProperty<T>
{

    private EnvInjectJobPropertyInfo info = new EnvInjectJobPropertyInfo();
    private boolean on;

    @SuppressWarnings("unused")
    public EnvInjectJobPropertyInfo getInfo()
    {
        return info;
    }

    @SuppressWarnings("unused")
    public boolean isOn()
    {
        return on;
    }

    public void setInfo(EnvInjectJobPropertyInfo info)
    {
        this.info = info;
    }

    public void setOn(boolean on)
    {
        this.on = on;
    }

    @Override
    public JobProperty<?> reconfigure(StaplerRequest req, JSONObject form) throws Descriptor.FormException
    {
        EnvInjectPrebuildJobProperty property = (EnvInjectPrebuildJobProperty) super.reconfigure(req, form);
        if (property != null && property.info != null && !Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS)) {
            // Don't let non RUN_SCRIPT users set arbitrary groovy script
            property.info =
                    new EnvInjectJobPropertyInfo(property.info.propertiesFilePath, property.info.propertiesContent,
                            property.info.getScriptFilePath(), property.info.getScriptContent(),
                            this.info != null ? this.info.getGroovyScriptContent() : "", property.info.isLoadFilesFromMaster());
        }
        return property;
    }

    @Extension
    @SuppressWarnings("unused")
    public static final class DescriptorImpl extends JobPropertyDescriptor
    {

        @Override
        public String getDisplayName()
        {
            return "[Environment Inject] -" + Messages.envinject_set_displayName();
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType)
        {
            return true;
        }

        @Override
        public String getHelpFile()
        {
            return "/plugin/envinject/help.html";
        }

        @Override
        public EnvInjectPrebuildJobProperty newInstance(StaplerRequest req, JSONObject formData) throws FormException
        {
            Object onObject = formData.get("on");

            if (onObject != null) {
                EnvInjectPrebuildJobProperty envInjectJobProperty = new EnvInjectPrebuildJobProperty();
                EnvInjectJobPropertyInfo info =
                        req.bindParameters(EnvInjectJobPropertyInfo.class, "envInjectInfoPrebuildJobProperty.");
                envInjectJobProperty.setInfo(info);
                envInjectJobProperty.setOn(true);

                return envInjectJobProperty;
            }

            return null;
        }
    }

    /* (non-Javadoc)
     * @see hudson.model.JobProperty#prebuild(hudson.model.AbstractBuild, hudson.model.BuildListener)
     */
    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener)
    {
        EnvInjectLogger logger = new EnvInjectLogger(listener);
        FilePath ws = build.getWorkspace();

        try {
            logger.info("Preparing an environment for the build (prebuild phase).");

            EnvInjectVariableGetter variableGetter = new EnvInjectVariableGetter();
            EnvInjectJobPropertyInfo info = getInfo();
            assert isOn();

            //Init infra env vars
            Map<String, String> previousEnvVars = variableGetter.getEnvVarsPreviousSteps(build, logger);
            Map<String, String> infraEnvVarsNode = new LinkedHashMap<String, String>(previousEnvVars);
            Map<String, String> infraEnvVarsMaster = new LinkedHashMap<String, String>(previousEnvVars);

            //Add workspace if not set
            if (ws != null) {
                if (infraEnvVarsNode.get("WORKSPACE") == null) {
                    infraEnvVarsNode.put("WORKSPACE", ws.getRemote());
                }
            }

            //Add Jenkins System variables
            logger.info("Keeping Jenkins system variables.");
            infraEnvVarsMaster.putAll(variableGetter.getJenkinsSystemVariables(true));
            infraEnvVarsNode.putAll(variableGetter.getJenkinsSystemVariables(false));

            //Add build variables
            logger.info("Keeping Jenkins build variables.");
            Map<String, String> buildVariables = variableGetter.getBuildVariables(build, logger);
            infraEnvVarsMaster.putAll(buildVariables);
            infraEnvVarsNode.putAll(buildVariables);

            final FilePath rootPath = getNodeRootPath();
            if (rootPath != null) {

                final EnvInjectEnvVars envInjectEnvVarsService = new EnvInjectEnvVars(logger);

                //Execute script
                int resultCode =
                        envInjectEnvVarsService.executeScript(info.isLoadFilesFromMaster(), info.getScriptContent(), rootPath,
                                info.getScriptFilePath(), infraEnvVarsMaster, infraEnvVarsNode,
                                rootPath.createLauncher(listener), listener);
                if (resultCode != 0) {
                    logger.error("Remote script " + rootPath + " returned an error: " + resultCode);
                    return false;
                }

                //Evaluate Groovy script
                envInjectEnvVarsService.executeAndGetMapGroovyScript(logger, info.getGroovyScriptContent(), infraEnvVarsNode);
            }
        }
        catch (Throwable t) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            logger.error("Failure while executing scripts in a prebuild phase: " + sw.toString());
            pw.close();
            return false;
        }
        return true;
    }

    private Node getNode()
    {
        Computer computer = Computer.currentComputer();
        if (computer == null) {
            return null;
        }
        return computer.getNode();
    }

    private FilePath getNodeRootPath()
    {
        Node node = getNode();
        if (node != null) {
            return node.getRootPath();
        }
        return null;
    }
}
