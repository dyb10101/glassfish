/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2006-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.deployment.admin;

import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.admin.FailurePolicy;
import com.sun.enterprise.config.serverbeans.ApplicationRef;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.Application;
import com.sun.enterprise.config.serverbeans.Applications;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.admin.util.ClusterOperationUtil;
import org.glassfish.api.ActionReport;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.deployment.UndeployCommandParameters;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.api.admin.Cluster;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.internal.deployment.Deployment;
import org.glassfish.internal.deployment.ExtendedDeploymentContext;
import org.glassfish.internal.data.ApplicationInfo;
import org.glassfish.config.support.TargetType;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.common.util.admin.ParameterMapExtractor;
import org.glassfish.deployment.common.DeploymentUtils;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.component.PerLookup;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.SingleConfigCode;
import org.jvnet.hk2.config.TransactionFailure;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.beans.PropertyVetoException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Collections;

import org.glassfish.deployment.versioning.VersioningService;
import org.glassfish.deployment.common.VersioningDeploymentException;
import org.glassfish.deployment.common.VersioningDeploymentSyntaxException;

/**
 * Disable command
 */
@Service(name="disable")
@I18n("disable.command")
@Cluster(value={RuntimeType.DAS, RuntimeType.INSTANCE})
@Scoped(PerLookup.class)
@TargetType(value={CommandTarget.DOMAIN, CommandTarget.DAS, CommandTarget.STANDALONE_INSTANCE, CommandTarget.CLUSTER, CommandTarget.CLUSTERED_INSTANCE})
public class DisableCommand extends UndeployCommandParameters implements AdminCommand {

    final private static LocalStringManagerImpl localStrings = new LocalStringManagerImpl(DisableCommand.class);    

    @Param(optional=true, defaultValue="false")
    public Boolean isUndeploy = false;

    @Inject
    ServerEnvironment env;

    @Inject
    Deployment deployment;

    @Inject
    Domain domain;

    @Inject
    Applications applications;

    @Inject(name= ServerEnvironment.DEFAULT_INSTANCE_NAME)
    protected Server server;

    @Inject
    VersioningService versioningService;

    @Inject
    Habitat habitat;

    public DisableCommand() {
        origin = Origin.unload;
    }

    /**
     * Entry point from the framework into the command execution
     * @param context context for the command.
     */
    public void execute(AdminCommandContext context) {

        final ActionReport report = context.getActionReport();
        final Logger logger = context.getLogger();

        String appName = name();

        if (isUndeploy) {
            origin = Origin.undeploy;
        }

        try {
            List<String> matchedVersions = versioningService.getMatchedVersions(appName, target);
            if (matchedVersions == Collections.EMPTY_LIST) {
                // no version matched by the expression
                // nothing to do : success
                report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
                return;
            }
            String enabledVersion = versioningService.getEnabledVersion(appName, target);
            if (matchedVersions.contains(enabledVersion)) {
                // the enabled version is matched by the expression
                appName = enabledVersion;
            } else {
                // the enabled version is not matched by the expression
                // nothing to do : success
                report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
                return;
            }
        } catch (VersioningDeploymentSyntaxException e) {
            report.failure(logger, e.getMessage());
            return;
        } catch (VersioningDeploymentException e) {
            report.failure(logger, e.getMessage());
            return;
        }

        if (!deployment.isRegistered(appName)) {
            report.setMessage(localStrings.getLocalString("application.notreg","Application {0} not registered", appName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }

        if (!DeploymentUtils.isDomainTarget(target)) {
            ApplicationRef ref = domain.getApplicationRefInTarget(appName, target);
            if (ref == null) {
                report.setMessage(localStrings.getLocalString("ref.not.referenced.target","Application {0} is not referenced by target {1}", appName, target));
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                return;
            }
        }


        if (env.isDas()) {
            if (DeploymentUtils.isDomainTarget(target)) {
                List<String> targets = domain.getAllReferencedTargetsForApplication(appName);
                // replicate command to all referenced targets
                try {
                    ParameterMapExtractor extractor = new ParameterMapExtractor(this);
                    ParameterMap paramMap = extractor.extract(Collections.EMPTY_LIST);
                    paramMap.set("DEFAULT", appName);
                    ClusterOperationUtil.replicateCommand("disable", FailurePolicy.Error, FailurePolicy.Warn, targets, context, paramMap, habitat);
                } catch (Exception e) {
                    report.failure(logger, e.getMessage());
                    return;
                } 
            }
        }

        if (!domain.isCurrentInstanceMatchingTarget(target, appName, server.getName(), null)) {
            try {
                deployment.updateAppEnabledAttributeInDomainXML(appName, target, false);
            } catch(TransactionFailure e) {
                logger.warning("failed to set enable attribute for " + appName);
            }
            return;
        }

        ApplicationInfo appInfo = deployment.get(appName);

        try {
            Application app = applications.getApplication(appName);

            this.name = appName;

            deployment.disable(this, app, appInfo, report, logger);

            if (!report.getActionExitCode().equals(ActionReport.ExitCode.FAILURE)) {
                try {
                    deployment.updateAppEnabledAttributeInDomainXML(appName, target, false);
                } catch(TransactionFailure e) {
                    logger.warning("failed to set enable attribute for " + appName);
                }
            }
        } catch(Exception e) {
            logger.log(Level.SEVERE, "Error during disabling: ", e);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(e.getMessage());
        }
    }        
}
