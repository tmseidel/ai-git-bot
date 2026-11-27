/*
 * Formats the raw values the server renders (instants, durations, numbers, shares) in the page's
 * locale (<html lang>) and the browser's time zone. The server's text is the fallback without
 * JavaScript. The whole page is formatted once it has loaded; content added later (the details
 * modal) is formatted with formatLocale(root).
 *
 *   <time datetime="2026-11-20T08:30:00Z" data-format="datetime|time">
 *   <span data-duration-ms="1234">   <span data-number="42">   <span data-percent="0.5">
 */
(function () {
    let formats;

    /** The formatters, built on first use: the page's locale does not change. */
    function getFormats() {
        if (!formats) {
            const locale = document.documentElement.lang || undefined;
            const unit = function (unit, digits) {
                return new Intl.NumberFormat(locale,
                    {style: 'unit', unit: unit, unitDisplay: 'short', maximumFractionDigits: digits});
            };
            formats = {
                number: new Intl.NumberFormat(locale),
                percent: new Intl.NumberFormat(locale, {style: 'percent', maximumFractionDigits: 1}),
                seconds: unit('second', 1),
                wholeSeconds: unit('second', 0),
                minutes: unit('minute', 0),
                dateTime: new Intl.DateTimeFormat(locale, {dateStyle: 'medium', timeStyle: 'medium'}),
                time: new Intl.DateTimeFormat(locale, {timeStyle: 'medium'})
            };
        }
        return formats;
    }

    /** Under a minute to a tenth of a second ("12.3 s"), else in whole seconds ("2 min 5 s"). */
    function formatDuration(ms, f) {
        const tenths = Math.round(ms / 100);
        if (tenths < 600) {
            return f.seconds.format(tenths / 10);
        }
        // Round before splitting, or 119.6 s would read "1 min 60 s".
        const total = Math.round(ms / 1000);
        return f.minutes.format(Math.floor(total / 60)) + ' ' + f.wholeSeconds.format(total % 60);
    }

    function formatInstant(iso, format, f) {
        const date = new Date(iso);
        if (isNaN(date.getTime())) {
            return iso;
        }
        return format === 'time' ? f.time.format(date) : f.dateTime.format(date);
    }

    window.formatLocale = function (root) {
        const f = getFormats();
        root.querySelectorAll('time[datetime]').forEach(function (el) {
            el.textContent = formatInstant(el.getAttribute('datetime'), el.dataset.format, f);
        });
        root.querySelectorAll('[data-duration-ms]').forEach(function (el) {
            el.textContent = formatDuration(Number(el.dataset.durationMs), f);
        });
        root.querySelectorAll('[data-number]').forEach(function (el) {
            el.textContent = f.number.format(Number(el.dataset.number));
        });
        root.querySelectorAll('[data-percent]').forEach(function (el) {
            el.textContent = f.percent.format(Number(el.dataset.percent));
        });
    };

    document.addEventListener('DOMContentLoaded', function () {
        window.formatLocale(document);
    });
})();
