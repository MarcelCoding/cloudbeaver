/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2020 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cloudbeaver.service.data.transfer.impl;

import io.cloudbeaver.DBWebException;
import io.cloudbeaver.server.CBPlatform;
import io.cloudbeaver.model.WebAsyncTaskInfo;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.service.sql.WebSQLContextInfo;
import io.cloudbeaver.service.sql.WebSQLProcessor;
import io.cloudbeaver.service.data.transfer.DBWServiceDataTransfer;
import io.cloudbeaver.service.sql.WebSQLQueryDataContainer;
import io.cloudbeaver.service.sql.WebSQLResultsInfo;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithResult;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.tools.transfer.IDataTransferConsumer;
import org.jkiss.dbeaver.tools.transfer.IDataTransferProcessor;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseProducerSettings;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseTransferProducer;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.dbeaver.tools.transfer.stream.IStreamDataExporter;
import org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferConsumer;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.utils.CommonUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Web service implementation
 */
public class WebServiceDataTransfer implements DBWServiceDataTransfer {

    private static final Log log = Log.getLog(WebServiceDataTransfer.class);

    private final File dataExportFolder;

    public WebServiceDataTransfer() {
        dataExportFolder = CBPlatform.getInstance().getTempFolder(new VoidProgressMonitor(), "data-transfer");

        ContentUtils.deleteFileRecursive(dataExportFolder);
        if (!dataExportFolder.mkdirs()) {
            log.error("Error re-creating temporary folder");
        }
    }

    public File getDataExportFolder() {
        return dataExportFolder;
    }

    @Override
    public List<WebDataTransferStreamProcessor> getAvailableStreamProcessors(WebSession session) {
        List<DataTransferProcessorDescriptor> processors = DataTransferRegistry.getInstance().getAvailableProcessors(StreamTransferConsumer.class, DBSEntity.class);
        if (CommonUtils.isEmpty(processors)) {
            return Collections.emptyList();
        }

        return processors.stream().map(x -> new WebDataTransferStreamProcessor(session, x)).collect(Collectors.toList());
    }

    @Override
    public WebAsyncTaskInfo dataTransferExportDataFromContainer(
        WebSQLProcessor sqlProcessor,
        String containerNodePath,
        WebDataTransferParameters parameters) throws DBWebException {

        DBSDataContainer dataContainer;
        try {
            dataContainer = sqlProcessor.getDataContainerByNodePath(sqlProcessor.getWebSession().getProgressMonitor(), containerNodePath, DBSDataContainer.class);
        } catch (DBException e) {
            throw new DBWebException("Invalid node path: " + containerNodePath, e);
        }

        return asyncExportFromDataContainer(sqlProcessor, parameters, dataContainer);
    }

    @NotNull
    private String makeUniqueFileName(WebSQLProcessor sqlProcessor, DataTransferProcessorDescriptor processor) {
        return sqlProcessor.getWebSession().getId() + "_" + UUID.randomUUID() + "." + WebDataTransferUtils.getProcessorFileExtension(processor);
    }

    @Override
    public WebAsyncTaskInfo dataTransferExportDataFromResults(
        WebSQLContextInfo sqlContext,
        String resultsId,
        WebDataTransferParameters parameters) throws DBWebException {

        WebSQLResultsInfo results = sqlContext.getResults(resultsId);

        return asyncExportFromDataContainer(sqlContext.getProcessor(), parameters, results.getDataContainer());
    }

    @Override
    public Boolean dataTransferRemoveDataFile(WebSQLProcessor sqlProcessor, String dataFileId) {
        return true;
    }

    private WebAsyncTaskInfo asyncExportFromDataContainer(WebSQLProcessor sqlProcessor, WebDataTransferParameters parameters, DBSDataContainer dataContainer) {
        DataTransferProcessorDescriptor processor = DataTransferRegistry.getInstance().getProcessor(parameters.getProcessorId());
        DBRRunnableWithResult<String> runnable = new DBRRunnableWithResult<String>() {
            @Override
            public void run(DBRProgressMonitor monitor) throws InvocationTargetException {
                try {
                    File exportFile = new File(dataExportFolder, makeUniqueFileName(sqlProcessor, processor));
                    try {
                        exportData(monitor, processor, dataContainer, parameters, exportFile);
                    } catch (Exception e) {
                        if (exportFile.exists()) {
                            if (!exportFile.delete()) {
                                log.error("Error deleting export file " + exportFile.getAbsolutePath());
                            }
                        }
                        throw new DBException("Error exporting data", e);
                    }
                    WebDataTransferTaskConfig taskConfig = new WebDataTransferTaskConfig(exportFile, parameters);
                    String exportFileName = CommonUtils.escapeFileName(CommonUtils.truncateString(dataContainer.getName(), 32));
                    taskConfig.setExportFileName(exportFileName);
                    WebDataTransferUtils.getSessionDataTransferConfig(sqlProcessor.getWebSession()).addTask(taskConfig);

                    result = exportFile.getName();
                } catch (Throwable e) {
                    throw new InvocationTargetException(e);
                }
            }
        };
        return sqlProcessor.getWebSession().createAndRunAsyncTask("Data export", runnable);
    }

    private void exportData(
        DBRProgressMonitor monitor,
        DataTransferProcessorDescriptor processor,
        DBSDataContainer dataContainer,
        WebDataTransferParameters parameters,
        File exportFile) throws DBException, IOException
    {
        IDataTransferProcessor processorInstance = processor.getInstance();
        if (!(processorInstance instanceof IStreamDataExporter)) {
            throw new DBException("Invalid processor. " + IStreamDataExporter.class.getSimpleName() + " expected");
        }
        IStreamDataExporter exporter = (IStreamDataExporter) processorInstance;

        StreamTransferConsumer consumer = new StreamTransferConsumer();
        StreamConsumerSettings settings = new StreamConsumerSettings();

        settings.setOutputEncodingBOM(false);
        settings.setOpenFolderOnFinish(false);
        settings.setOutputFolder(exportFile.getParentFile().getAbsolutePath());
        settings.setOutputFilePattern(exportFile.getName());

        Map<Object, Object> properties = new HashMap<>();

        Map<String, Object> processorProperties = parameters.getProcessorProperties();
        if (processorProperties == null) processorProperties = Collections.emptyMap();
        for (DBPPropertyDescriptor prop : processor.getProperties()) {
            Object propValue = processorProperties.get(CommonUtils.toString(prop.getId()));
            properties.put(prop.getId(), propValue != null ? propValue : prop.getDefaultValue());
        }
        // Remove extension property (we specify file name directly)
        properties.remove(StreamConsumerSettings.PROP_FILE_EXTENSION);

        consumer.initTransfer(
            dataContainer,
            settings,
            new IDataTransferConsumer.TransferParameters(processor.isBinaryFormat(), processor.isHTMLFormat()),
            exporter,
            properties);

        DatabaseTransferProducer producer = new DatabaseTransferProducer(dataContainer, parameters.getFilter() == null ? null : parameters.getFilter().makeDataFilter());
        DatabaseProducerSettings producerSettings = new DatabaseProducerSettings();
        producerSettings.setExtractType(DatabaseProducerSettings.ExtractType.SINGLE_QUERY);
        producerSettings.setQueryRowCount(false);
        producerSettings.setOpenNewConnections(CommonUtils.getOption(parameters.getSettings(), "openNewConnection"));

        producer.transferData(monitor, consumer, null, producerSettings, null);

        consumer.finishTransfer(monitor, false);
    }
}
