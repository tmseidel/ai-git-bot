// Review quality page: draws the charts from the JSON the server rendered into #reviewQualityCharts
// (one spec per canvas id) and shows the date fields of a custom period.
(function () {
    'use strict';

    var period = document.getElementById('filterPeriod');
    function toggleCustomPeriod() {
        document.querySelectorAll('#reviewQualityFilters .custom-period').forEach(function (field) {
            field.classList.toggle('d-none', period.value !== 'custom');
        });
    }
    if (period) {
        period.addEventListener('change', toggleCustomPeriod);
        toggleCustomPeriod();
    }

    var source = document.getElementById('reviewQualityCharts');
    if (!source || !window.Chart) {
        return;
    }
    var specs = JSON.parse(source.textContent || '{}');
    var css = getComputedStyle(document.documentElement);
    Chart.defaults.font.size = 11;
    Chart.defaults.plugins.legend.labels.boxWidth = 10;
    Chart.defaults.color = css.getPropertyValue('--bs-secondary-color').trim() || Chart.defaults.color;
    Chart.defaults.borderColor = css.getPropertyValue('--bs-border-color-translucent').trim() || Chart.defaults.borderColor;

    function datasets(spec, series) {
        return series.map(function (s) {
            var line = s.type === 'line';
            return {
                type: line ? 'line' : 'bar',
                label: s.label,
                data: s.data,
                backgroundColor: s.colors || s.color,
                borderColor: s.color,
                yAxisID: spec.kind === 'hbar' ? undefined : (s.axis === 'y2' ? 'y2' : 'y'),
                stack: spec.kind === 'stacked' && !line ? 'stack' : undefined,
                order: line ? 0 : 1,
                cubicInterpolationMode: 'monotone',
                pointRadius: 2,
                spanGaps: true
            };
        });
    }

    function config(spec, series, yTitle) {
        if (spec.kind === 'doughnut') {
            var s = series[0];
            return {
                type: 'doughnut',
                data: {labels: spec.labels, datasets: [{data: s.data, backgroundColor: s.colors}]},
                options: {maintainAspectRatio: false, plugins: {legend: {position: 'bottom'}}}
            };
        }
        if (spec.kind === 'hbar') {
            return {
                type: 'bar',
                data: {labels: spec.labels, datasets: datasets(spec, series)},
                options: {indexAxis: 'y', maintainAspectRatio: false}
            };
        }
        var stacked = spec.kind === 'stacked';
        var scales = {
            x: {stacked: stacked},
            y: {stacked: stacked, beginAtZero: true, title: {display: !!yTitle, text: yTitle}}
        };
        if (series.some(function (s) { return s.axis === 'y2'; })) {
            scales.y2 = {
                position: 'right', beginAtZero: true, max: spec.y2Max, grid: {display: false},
                title: {display: !!spec.y2Title, text: spec.y2Title}
            };
        }
        return {
            type: spec.kind === 'line' ? 'line' : 'bar',
            data: {labels: spec.labels, datasets: datasets(spec, series)},
            options: {maintainAspectRatio: false, scales: scales}
        };
    }

    var charts = {};
    Object.keys(specs).forEach(function (id) {
        var canvas = document.getElementById(id);
        if (canvas) {
            charts[id] = new Chart(canvas, config(specs[id], specs[id].series, specs[id].yTitle));
        }
    });

    // Findings per PR or per 1,000 changed lines on the Overview tab.
    document.querySelectorAll('input[name="trendNorm"]').forEach(function (radio) {
        radio.addEventListener('change', function () {
            var spec = specs.trendChart;
            if (!spec || !charts.trendChart) {
                return;
            }
            var perLines = radio.value === 'kloc' && radio.checked;
            charts.trendChart.destroy();
            charts.trendChart = new Chart(document.getElementById('trendChart'),
                config(spec, perLines ? spec.alternative : spec.series,
                    perLines ? spec.yTitleAlternative : spec.yTitle));
        });
    });
})();
