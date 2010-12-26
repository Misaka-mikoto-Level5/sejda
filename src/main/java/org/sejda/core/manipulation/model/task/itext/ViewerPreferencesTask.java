/*
 * Created on 21/set/2010
 *
 * Copyright 2010 by Andrea Vacondio (andrea.vacondio@gmail.com).
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.sejda.core.manipulation.model.task.itext;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.sejda.core.exception.TaskException;
import org.sejda.core.exception.TaskExecutionException;
import org.sejda.core.manipulation.model.input.PdfSource;
import org.sejda.core.manipulation.model.parameter.ViewerPreferencesParameters;
import org.sejda.core.manipulation.model.pdf.viewerpreferences.PdfBooleanPreference;
import org.sejda.core.manipulation.model.task.Task;
import org.sejda.core.manipulation.model.task.itext.component.PdfStamperHandler;
import org.sejda.core.manipulation.model.task.itext.util.ViewerPreferencesUtils;
import org.sejda.core.support.io.MultipleOutputWriterSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lowagie.text.pdf.PdfBoolean;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;

import static org.sejda.core.manipulation.model.task.itext.util.ITextUtils.nullSafeClosePdfReader;
import static org.sejda.core.manipulation.model.task.itext.util.ITextUtils.nullSafeClosePdfStamperHandler;
import static org.sejda.core.manipulation.model.task.itext.util.PdfReaderUtils.openReader;

import static org.sejda.core.notification.dsl.ApplicationEventsNotifier.notifyEvent;
import static org.sejda.core.support.io.model.FileOutput.file;
import static org.sejda.core.support.perfix.NameGenerator.nameGenerator;
import static org.sejda.core.support.perfix.model.NameGenerationRequest.nameRequest;

/**
 * Task setting viewer preferences on a list of {@link PdfSource}.
 * 
 * @author Andrea Vacondio
 * 
 */
public class ViewerPreferencesTask extends MultipleOutputWriterSupport implements Task<ViewerPreferencesParameters> {

    private static final Logger LOG = LoggerFactory.getLogger(ViewerPreferencesTask.class);

    private PdfReader reader = null;
    private PdfStamperHandler stamperHandler = null;
    private int totalSteps;
    private int preferences;
    private Map<PdfName, PdfObject> configuredPreferences;

    public void before(ViewerPreferencesParameters parameters) throws TaskExecutionException {
        totalSteps = parameters.getSourceList().size() + 1;
        preferences = ViewerPreferencesUtils.getViewerPreferences(parameters.getPageMode(), parameters.getPageLayout());
        configuredPreferences = getConfiguredViewerPreferencesMap(parameters);
        if (LOG.isDebugEnabled()) {
            LOG.debug("The following preferences will be set on the input pdf sources:");
            for (Entry<PdfName, PdfObject> entry : configuredPreferences.entrySet()) {
                LOG.debug(String.format("%s = %s", entry.getKey(), entry.getValue()));
            }
            LOG.debug(String.format("Page mode = %s", parameters.getPageMode()));
            LOG.debug(String.format("Page layout = %s", parameters.getPageLayout()));
        }
    }

    public void execute(ViewerPreferencesParameters parameters) throws TaskException {
        int currentStep = 0;

        for (PdfSource source : parameters.getSourceList()) {
            currentStep++;
            LOG.debug("Opening {} ...", source);
            reader = openReader(source, true);

            File tmpFile = createTemporaryPdfBuffer();
            LOG.debug("Creating output on temporary buffer {} ...", tmpFile);
            stamperHandler = new PdfStamperHandler(reader, tmpFile, parameters.getVersion());

            stamperHandler.setCompressionOnStamper(parameters.isCompressXref());
            stamperHandler.setCreatorOnStamper(reader);
            // set mode and layout
            stamperHandler.setViewerPreferencesOnStamper(preferences);

            // set other preferences
            for (Entry<PdfName, PdfObject> entry : configuredPreferences.entrySet()) {
                stamperHandler.addViewerPreferenceOnStamper(entry.getKey(), entry.getValue());
            }

            nullSafeClosePdfReader(reader);
            nullSafeClosePdfStamperHandler(stamperHandler);

            String outName = nameGenerator(parameters.getOutputPrefix(), source.getName()).generate(nameRequest());
            addOutput(file(tmpFile).name(outName));

            notifyEvent().stepsCompleted(currentStep).outOf(totalSteps);
        }

        flushOutputs(parameters.getOutput(), parameters.isOverwrite());
        notifyEvent().stepsCompleted(++currentStep).outOf(totalSteps);

        LOG.debug("Viewer preferences set on input documents and written to {}", parameters.getOutput());

    }

    public void after() {
        nullSafeClosePdfReader(reader);
        nullSafeClosePdfStamperHandler(stamperHandler);
    }

    /**
     * 
     * @param parameters
     * @return a map of preferences with corresponding value to be set on the documents
     */
    private Map<PdfName, PdfObject> getConfiguredViewerPreferencesMap(ViewerPreferencesParameters parameters) {
        Map<PdfName, PdfObject> confPreferences = new HashMap<PdfName, PdfObject>();
        if (parameters.getDirection() != null) {
            confPreferences.put(PdfName.DIRECTION, ViewerPreferencesUtils.getDirection(parameters.getDirection()));
        }
        if (parameters.getDuplex() != null) {
            confPreferences.put(PdfName.DUPLEX, ViewerPreferencesUtils.getDuplex(parameters.getDuplex()));
        }
        if (parameters.getPrintScaling() != null) {
            confPreferences.put(PdfName.PRINTSCALING, ViewerPreferencesUtils.getPrintScaling(parameters
                    .getPrintScaling()));
        }
        confPreferences.put(PdfName.NONFULLSCREENPAGEMODE, ViewerPreferencesUtils.getNFSMode(parameters.getNfsMode()));

        Set<PdfBooleanPreference> activePref = parameters.getActivePreferences();
        for (PdfBooleanPreference boolPref : PdfBooleanPreference.values()) {
            if (activePref.contains(boolPref)) {
                confPreferences.put(ViewerPreferencesUtils.getBooleanPreference(boolPref), PdfBoolean.PDFTRUE);
            } else {
                confPreferences.put(ViewerPreferencesUtils.getBooleanPreference(boolPref), PdfBoolean.PDFFALSE);
            }
        }
        return confPreferences;
    }
}
