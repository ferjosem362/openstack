/*
 * Copyright 2017 Banco Bilbao Vizcaya Argentaria, S.A.
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

package com.bbva.arq.devops.ae.mirrorgate.service;

import com.bbva.arq.devops.ae.mirrorgate.core.dto.DashboardDTO;
import com.bbva.arq.devops.ae.mirrorgate.core.dto.UserMetricDTO;
import com.bbva.arq.devops.ae.mirrorgate.dto.HistoricUserMetricDTO;
import com.bbva.arq.devops.ae.mirrorgate.mapper.UserMetricMapper;
import com.bbva.arq.devops.ae.mirrorgate.model.UserMetric;
import com.bbva.arq.devops.ae.mirrorgate.repository.UserMetricsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class UserMetricsServiceImpl implements UserMetricsService {

    private final DashboardService dashboardService;
    private final UserMetricsRepository userMetricsRepository;
    private final HistoricUserMetricService historicUserMetricService;

    @Autowired
    public UserMetricsServiceImpl(DashboardService dashboardService, UserMetricsRepository userMetricsRepository
                                , HistoricUserMetricService historicUserMetricService){
        this.dashboardService = dashboardService;
        this.userMetricsRepository = userMetricsRepository;
        this.historicUserMetricService = historicUserMetricService;
    }

    @Override
    public List<String> getAnalyticViewIds() {
        return dashboardService.getActiveDashboards().stream()
                .flatMap((d) -> d.getAnalyticViews() == null ?
                        Stream.empty() :
                        d.getAnalyticViews().stream()
                )
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<UserMetricDTO> getMetricsByCollectorId(String collectorId) {
        return userMetricsRepository.findAllByCollectorId(collectorId).stream()
                .map(UserMetricMapper::map).collect(Collectors.toList());
    }

    @Override
    public List<UserMetricDTO> saveMetrics(Iterable<UserMetricDTO> metrics) {
        List<UserMetric> toSave = StreamSupport.stream(metrics.spliterator(), false)
                .map(UserMetricMapper::map)
                .collect(Collectors.toList());

        Iterable<UserMetric> saved = userMetricsRepository.save(toSave);

        //send to historic
        historicUserMetricService.addToCurrentPeriod(saved);

        return toSave.stream().map(UserMetricMapper::map).collect(Collectors.toList());
    }

    @Override
    public List<UserMetricDTO> getMetricsForDashboard(DashboardDTO dashboard) {
        List<String> views = dashboard.getAnalyticViews();
        if (views == null || views.isEmpty()) {
            return new ArrayList<>();
        }

        return userMetricsRepository.findAllByViewIdInWithNon0Values(views)
                    .stream()
                    .map(UserMetricMapper::map)
                    .map(u ->  {
                        List<HistoricUserMetricDTO> historicUserMetrics =
                            historicUserMetricService.getHistoricMetricsForDashboard(dashboard,u.getName(),24);

                        if(!historicUserMetrics.isEmpty()){
                            u.setLongTermTendency(getLongTermTendency(historicUserMetrics));
                            u.setShortTermTendency(getShortTermTendency(historicUserMetrics, 3l));
                        }

                        return u;
                    }).collect(Collectors.toList());
    }

    private double getLongTermTendency(List<HistoricUserMetricDTO> historicUserMetrics){
        return historicUserMetrics.stream()
                    .mapToDouble(HistoricUserMetricDTO::getValue)
                    .sum()/historicUserMetrics.size();
    }

    private double getShortTermTendency(List<HistoricUserMetricDTO> historicUserMetrics, long limit){
        return historicUserMetrics.stream()
                    .limit(limit)
                    .mapToDouble(HistoricUserMetricDTO::getValue)
                    .sum()/limit;
    }

}
