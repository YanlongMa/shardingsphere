/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.scaling.core.job.preparer;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.scaling.core.config.TaskConfiguration;
import org.apache.shardingsphere.scaling.core.datasource.DataSourceManager;
import org.apache.shardingsphere.scaling.core.exception.PrepareFailedException;
import org.apache.shardingsphere.scaling.core.job.ScalingJob;
import org.apache.shardingsphere.scaling.core.job.position.Position;
import org.apache.shardingsphere.scaling.core.job.position.PositionInitializerFactory;
import org.apache.shardingsphere.scaling.core.job.preparer.checker.DataSourceChecker;
import org.apache.shardingsphere.scaling.core.job.preparer.checker.DataSourceCheckerFactory;
import org.apache.shardingsphere.scaling.core.job.preparer.splitter.InventoryTaskSplitter;
import org.apache.shardingsphere.scaling.core.job.task.DefaultScalingTaskFactory;
import org.apache.shardingsphere.scaling.core.job.task.ScalingTask;
import org.apache.shardingsphere.scaling.core.job.task.ScalingTaskFactory;
import org.apache.shardingsphere.scaling.core.schedule.JobStatus;
import org.apache.shardingsphere.scaling.core.utils.ScalingConfigurationUtil;

import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;

/**
 * Scaling job preparer.
 */
@Slf4j
public final class ScalingJobPreparer {
    
    private final ScalingTaskFactory scalingTaskFactory = new DefaultScalingTaskFactory();
    
    private final InventoryTaskSplitter inventoryTaskSplitter = new InventoryTaskSplitter();
    
    /**
     * Do prepare work for scaling job.
     *
     * @param scalingJob scaling job
     */
    public void prepare(final ScalingJob scalingJob) {
        ScalingConfigurationUtil.fillInProperties(scalingJob.getScalingConfig());
        try (DataSourceManager dataSourceManager = new DataSourceManager(scalingJob.getTaskConfigs())) {
            checkDataSource(scalingJob, dataSourceManager);
            initIncrementalTasks(scalingJob, dataSourceManager);
            initInventoryTasks(scalingJob, dataSourceManager);
        } catch (final PrepareFailedException | SQLException ex) {
            log.error("Preparing scaling job {} failed", scalingJob.getJobId(), ex);
            scalingJob.setStatus(JobStatus.PREPARING_FAILURE.name());
        }
    }
    
    private void checkDataSource(final ScalingJob scalingJob, final DataSourceManager dataSourceManager) {
        checkSourceDataSources(scalingJob, dataSourceManager);
        checkTargetDataSources(scalingJob, dataSourceManager);
    }
    
    private void checkSourceDataSources(final ScalingJob scalingJob, final DataSourceManager dataSourceManager) {
        DataSourceChecker dataSourceChecker = DataSourceCheckerFactory.newInstance(scalingJob.getDatabaseType());
        dataSourceChecker.checkConnection(dataSourceManager.getCachedDataSources().values());
        dataSourceChecker.checkPrivilege(dataSourceManager.getSourceDataSources().values());
        dataSourceChecker.checkVariable(dataSourceManager.getSourceDataSources().values());
    }
    
    private void checkTargetDataSources(final ScalingJob scalingJob, final DataSourceManager dataSourceManager) {
        DataSourceChecker dataSourceChecker = DataSourceCheckerFactory.newInstance(scalingJob.getDatabaseType());
        dataSourceChecker.checkTargetTable(dataSourceManager.getTargetDataSources().values(), scalingJob.getTaskConfigs().iterator().next().getImporterConfig().getShardingColumnsMap().keySet());
    }
    
    private void initInventoryTasks(final ScalingJob scalingJob, final DataSourceManager dataSourceManager) {
        List<ScalingTask> allInventoryTasks = new LinkedList<>();
        for (TaskConfiguration each : scalingJob.getTaskConfigs()) {
            allInventoryTasks.addAll(inventoryTaskSplitter.splitInventoryData(scalingJob, each, dataSourceManager));
        }
        scalingJob.getInventoryTasks().addAll(allInventoryTasks);
    }
    
    private void initIncrementalTasks(final ScalingJob scalingJob, final DataSourceManager dataSourceManager) throws SQLException {
        for (TaskConfiguration each : scalingJob.getTaskConfigs()) {
            each.getDumperConfig().setPosition(getIncrementalPosition(scalingJob, each, dataSourceManager));
            scalingJob.getIncrementalTasks().add(scalingTaskFactory.createIncrementalTask(each.getJobConfig().getConcurrency(), each.getDumperConfig(), each.getImporterConfig()));
        }
    }
    
    private Position<?> getIncrementalPosition(final ScalingJob scalingJob, final TaskConfiguration taskConfig, final DataSourceManager dataSourceManager) throws SQLException {
        if (null != scalingJob.getInitPosition()) {
            return scalingJob.getInitPosition().getIncrementalPosition(taskConfig.getDumperConfig().getDataSourceName());
        }
        return PositionInitializerFactory.newInstance(taskConfig.getJobConfig().getDatabaseType()).init(dataSourceManager.getDataSource(taskConfig.getDumperConfig().getDataSourceConfig()));
    }
}
