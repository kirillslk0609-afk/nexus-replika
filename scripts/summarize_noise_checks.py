#!/usr/bin/env python3
"""Summarize paired on-device ASR measurements, including every regression."""
import json,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'artifacts/test-results'
def main():
    before=json.loads((ART/'asr-noise-baseline.json').read_text())
    after=json.loads((ART/'asr-noise-regression.json').read_text())
    old={x['file']:x for x in before['cases']}
    assert set(old)=={x['file'] for x in after['cases']} and len(old)==45
    rows=[]
    for x in after['cases']:
        y=old[x['file']];assert x['reference']==y['reference'] and x['durationMs']==y['durationMs']
        assert x['baseline']['errors']==y['errors']
        rows.append({'file':x['file'],'language':x['language'],'noise':x['noise'],'snr_db':x['snr_db'],
            'reference_words':x['referenceWords'],'before_errors':y['errors'],'after_errors':x['errors'],
            'before_text':y['text'],'after_text':x['text'],'reference':x['reference'],'retries':x['retries'],
            'before_ms':y['recognitionMs'],'after_ms':x['recognitionMs'],'duration_ms':x['durationMs']})
    def group(selected):
        words=sum(x['reference_words'] for x in selected)
        b=sum(x['before_errors'] for x in selected);a=sum(x['after_errors'] for x in selected)
        return {'cases':len(selected),'reference_words':words,'before_errors':b,'after_errors':a,
                'before_wer':b/words,'after_wer':a/words,'relative_error_reduction':1-a/b if b else None}
    groups={'all':group(rows),'clean':group([x for x in rows if x['noise']=='clean']),'noisy':group([x for x in rows if x['noise']!='clean'])}
    for lang in ['ru','en']:
        for noise in ['clean','white','pink','hum']:
            groups[lang+'/'+noise]=group([x for x in rows if x['language']==lang and x['noise']==noise])
    regression=[x for x in rows if x['after_errors']>x['before_errors']]
    diar=json.loads((ART/'diarization-regression.json').read_text());baseline=json.loads((ART/'before-noise-v3/diarization-regression.json').read_text())
    assert [(x['fixture'],x['regions']) for x in diar]==[(x['fixture'],x['regions']) for x in baseline]
    report={'scope':'Paired ASR on identical 40ms-cropped float32 audio; 9 source recordings x 5 conditions, including 4 utterances by ONE LibriSpeech reader. Not a blind benchmark or full-pipeline WER.',
        'device':'Android 15 ARM64 emulator-5566; airplane mode; physical phone not tested',
        'baseline_apk_sha256':before['apk_sha256'],'current_apk_sha256':json.loads((ART/'summary.json').read_text())['apk_sha256'],
        'groups':groups,'regressions':regression,'retries':sum(x['retries'] for x in rows),
        'before_sampled_pss_mib':before['maxSampledPssKb']/1024,'after_sampled_pss_mib':after['maxSampledPssKb']/1024,
        'pss_caveat':'Maximum PSS sampled after ASR calls, not continuous peak RAM or physical-phone RAM.',
        'before_decode_seconds':sum(x['before_ms'] for x in rows)/1000,'after_decode_seconds':sum(x['after_ms'] for x in rows)/1000,
        'timing_caveat':'ASR calls including retries; excludes loading, diarization and LLM; emulator timings vary with host load.',
        'diarization_fixtures_exactly_unchanged':len(diar),'diarization_regions_exactly_unchanged':sum(len(x['regions']) for x in diar),'cases':rows}
    (ART/'noise-comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    lines=['# Шумная речь: ASR версии 0.3, повторная проверка в 0.4','',
        'Сравнение ниже: базовый ASR версии 0.2 и текущий ASR версии 0.4, идентичный версии 0.3. Все 45 текстов повторно сверены с 0.3 в SPEED_RESULTS.md. Различия относительно 0.2 возникли при изменении ASR в 0.3, а не при ускорении LLM в 0.4.', '',
        'Whisper base заменён на многоязычный Whisper small INT8. При явном цикле повторяющихся слов распознавание один раз повторяется с 200 мс тишины по краям. Принимается только непустой результат без обнаруженного цикла; иначе сохраняется исходный текст. Алгоритм не исправляет слова по эталону. Обработка выполняется локально, сеть выключена.',
        '', 'Вход диаризации, модели pyannote/TitaNet и кластеризация не изменены. С исходным прогоном 0.2 точно совпали ID и времена всех %d регионов на %d записях.'%(report['diarization_regions_exactly_unchanged'],len(diar)),
        '', '## Парное сравнение ASR на Android', '',
        'Обе версии проверены на одном Android 15 ARM64 эмуляторе. Девять исходных записей × пять условий = 45 случаев: чистый звук, белый шум 12 и 6 dB SNR, розовый шум 6 dB и гул 6 dB. Пять записей синтетические (три русских, два английских); четыре английских фрагмента — живой диктор LibriSpeech, один человек. На вход обеих версий поданы одинаковые float32 WAV со срезом первых 40 мс. Это сравнение ASR, отдельно от диаризации и LLM.',
        '', '| Группа | Случаев | Ошибки до → после | WER до → после |','|---|---:|---:|---:|']
    for key,g in groups.items():
        lines.append(f'| {key} | {g["cases"]} | {g["before_errors"]} → {g["after_errors"]} | {g["before_wer"]:.2%} → {g["after_wer"]:.2%} |')
    lines+=['','WER = (замены + удаления + вставки) / число слов эталона. Регистр и пунктуация игнорируются; числа цифрами и прописью не приравниваются, «е/ё» не объединяются. Отчёт содержит все случаи, включая ухудшения.', '', '## Ухудшившиеся случаи', '']
    for x in regression:lines.append(f'- `{x["file"]}`: {x["before_errors"]} → {x["after_errors"]} ошибок на {x["reference_words"]} словах.')
    if not regression:lines.append('На этой матрице не обнаружены.')
    lines+=['',f'Повторное декодирование сработало {report["retries"]} раз. Суммарное время ASR-вызовов: {report["before_decode_seconds"]:.1f} → {report["after_decode_seconds"]:.1f} с. Загрузка моделей, диаризация и LLM в эти времена не входят. Снимки PSS после ASR: максимум {report["before_sampled_pss_mib"]:.0f} → {report["after_sampled_pss_mib"]:.0f} МиБ; это не непрерывное измерение пика RAM. Скорость и память телефона не измерены.', '', '## Границы вывода', '',
        'Набор использовался при выборе решения, поэтому не является слепой независимой проверкой. Это искусственно добавленные шумы и короткие записи; живой русской речи с ручным эталоном здесь нет. Улучшение среднего WER не гарантирует улучшения любой фразы. При сильном шуме, обрывах и одновременной речи остаются ошибки. Повтор действительно произнесённых слов иногда тоже может вызвать повтор ASR; это эвристика, а не детектор истинности текста.',
        '', 'GTCRN и варианты спектрального шумоподавления были проверены и отклонены из-за ухудшения части русских примеров. Эти фильтры не включены в APK. Исходные эксперименты сохранены в `artifacts/noise-research/`.',
        '', '## Воспроизведение', '',
        '- Источники, лицензии, преобразования и SHA-256: `config/noise-fixtures.json`; чистые WAV и тексты: `samples/noise-sources/`.',
        '- Генерация PCM: `python3 scripts/generate_noise_fixtures.py` (нужен numpy). Готовые файлы уже находятся в test assets.',
        '- Android-тест вызывает тот же `SpeechRecognizer`, что приложение: `NoiseRegressionTest`. Эталоны доступны только тестовому APK.',
        '- Базовые результаты Android 0.2: `artifacts/test-results/asr-noise-baseline.json`; прежние ASR-веса закреплены в `config/asr-baseline-models.lock.json`. Для обновления baseline нужна отдельная сборка с этими весами и параметр instrumentation `noiseRecordBaseline=true`, отключающий retry.',
        '- Полный прогон: `scripts/run_android_checks.sh`; сводка: `python3 scripts/summarize_noise_checks.py`. JSON с текстами и всеми ошибками: `artifacts/test-results/noise-comparison.json`.',
        '- LibriSpeech: Vassil Panayotov и соавторы, [OpenSLR 12](https://www.openslr.org/12), CC BY 4.0; использована закреплённая копия Hugging Face (revision в конфигурации).','']
    (ROOT/'docs/NOISE_RESULTS.md').write_text('\n'.join(lines))
    print(json.dumps({k:v for k,v in report.items() if k not in ['cases','regressions']},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
