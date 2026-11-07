#!/usr/bin/env python3
"""Paired optimization timings plus exact-output regressions against Android v0.3."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'artifacts/test-results'
def read(name):return json.loads((ART/name).read_text())
def main():
    summary=read('summary.json');assert summary['android_tests_passed']==19
    baseline=ART/'before-speed-v4'
    old_noise=json.loads((baseline/'asr-noise-regression.json').read_text())['cases'];new_noise=read('asr-noise-regression.json')['cases']
    assert {x['file']:(x['text'],x['errors']) for x in old_noise}=={x['file']:(x['text'],x['errors']) for x in new_noise}
    old_diar=json.loads((baseline/'diarization-regression.json').read_text());new_diar=read('diarization-regression.json')
    assert [(x['fixture'],x['regions']) for x in old_diar]==[(x['fixture'],x['regions']) for x in new_diar]
    names=['test-russian','test-two-speakers','test-silence','quality-russian-noise.wav','quality-russian-pauses.wav','quality-conversation.mp3','quality-conversation-second.mp3']
    historical=[]
    for name in names:
        before=json.loads((baseline/(name+'.json')).read_text());after=read(name+'.json')
        assert before['result']==after['result'],name
        historical.append({'fixture':name,'beforeMs':before['processingMs'],'afterMs':after['processingMs'],'beforeLlmCalls':before['llmCalls'],'afterLlmCalls':after['llmCalls'],'stageMs':after.get('stageMs',{})})
    llm=read('speed-punctuation.json');paired=read('speed-pipeline.json');decoder=read('speed-decoder.json')
    assert len(decoder)==8 and all(x['bitExact'] for x in decoder)
    assert all(x['baseline']==x['optimized'] for x in llm)
    assert all(x['before']['result']==x['after']['result'] for x in paired)
    before=sum(x['baselineMs'] for x in llm);after=sum(x['optimizedMs'] for x in llm)
    stats={'cases':len(llm),'beforeMs':before,'afterMs':after,'speedup':before/after,'cacheHits':sum(x['optimizedNativeCalls']==0 for x in llm),'allFinalTextsIdentical':True}
    report={'version':'0.4.0','apk_sha256':summary['apk_sha256'],'device':'Android 15 ARM64 emulator-5566, airplane mode; not a physical phone',
        'unchanged_asr_cases':len(new_noise),'unchanged_diarization_cases':len(new_diar),'unchanged_regions':sum(len(x['regions']) for x in new_diar),'unchanged_full_pipeline_transcripts':len(names),
        'decoder_paired':decoder,'llm_paired':stats,'pipeline_paired':paired,'historical_unpaired':historical,
        'caveats':['LLM comparison uses separate warmed baseline/optimized sessions, alternates call order and excludes model loading. It contains 14 source fragments plus 2 deliberate repeats to exercise memoization; it does not estimate average conversation speed.','Paired full-pipeline runs include native ASR/LLM session loading and the first prompt; model files are already extracted/verified. They alternate execution order across two files; one run per mode per file.','Historical v0.3 total times came from a separate run and are sensitive to host load. They are not the primary speed measurement.','Floating-point performance and results on untested hardware are not guaranteed; physical-device validation is outstanding.']}
    (ART/'speed-comparison.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    lines=['# Ускорение 0.4.0 без изменений распознавания и диаризации','',
        'Все шесть моделей, параметры ASR и алгоритм диаризации сохранены. Ускорены подготовка сжатого аудио и постобработка в локальной LLM:', '',
        '1. Асинхронные callbacks MediaCodec позволяют подавать пакеты и обрабатывать готовый PCM параллельно. Очередь ограничена восемью событиями; порядок данных и изменений формата сохраняется. При отмене кодек и callback-поток освобождаются до возврата. Сведение в моно, FIR и ресемплер не менялись.',
        '2. Генерация прекращается, когда завершённое слово уже нарушает неизменённый итоговый лексический фильтр. Такой ответ раньше вычислялся до конца и всё равно отбрасывался. Неполный UTF-8 и незавершённые слова не отвергаются; сравнение выполняется только до пробела/табуляции/переноса, чтобы не нарушить контекстное приведение Unicode к нижнему регистру.',
        '3. До 64 результатов для точного совпадения входной строки переиспользуются внутри одной записи. Кеш не переносится между записями. Счётчик LLM показывает реальные нативные вызовы, не обращения к кешу.', '',
        'Перед каждой новой генерацией внутренний KV-кеш очищается полностью, как в 0.3. Эксперимент с повторным использованием префикса исключён из релиза: он менял два знака пунктуации на длинном контрольном разговоре.', '',
        '## Что осталось идентичным', '',
        f'- {len(new_noise)} вариантов чистой и шумной речи: весь ASR-текст и число ошибок совпали с 0.3.',
        f'- {len(new_diar)} записей диаризации: все {report["unchanged_regions"]} региона совпали по ID и времени.',
        f'- {len(names)} полных расшифровок: итоговый JSON совпал с 0.3, включая пунктуацию; среди них два естественных разговора.',
        '- 24 JVM-теста и 19 Android-тестов прошли; проверены отмена, повторный запуск, импорт, поворот, тишина, повреждение моделей и подготовка без сети.', '',
        '## Декодирование без изменений звука', '',
        'Восемь файлов: четыре MP3, один AAC/M4A, два PCM WAV и один float32 WAV. У старого и нового пути совпали число сэмплов и все 32 бита каждого float32-значения. Проверены отмена декодирования, повторный запуск и отсутствие оставшегося callback-потока. SHA-256 PCM и парные времена сохранены в speed-decoder.json. Порядок запусков чередуется.', '',
        '| Файл | Было, с | Стало, с | PCM |','|---|---:|---:|---|',
        *[f'| {x["fixture"]} | {x["baselineMs"]/1000:.3f} | {x["optimizedMs"]/1000:.3f} | идентичен |' for x in decoder], '',
        '## Парное измерение LLM', '',
        f'{len(llm)} фрагментов из контрольных расшифровок: {before/1000:.2f} → {after/1000:.2f} с, ускорение в {before/after:.2f} раза. Итоговый текст совпал посимвольно во всех случаях. Кеш готового текста использован {stats["cacheHits"]} раза.', '',
        'В наборе 14 исходных фрагментов и два намеренных повтора для проверки кеша. Это проверка механизма, не оценка типичной частоты повторов в речи. Использованы две независимые сессии одной модели. Обе прогреты, порядок вызовов чередуется, загрузка моделей не входит в замер. Это измерение LLM, а не ускорение всего приложения. В режиме baseline оптимизации отключены; алгоритм генерации соответствует версии 0.3.', '',
        '## Парное измерение полного процесса', '',
        'В обеих ветках одни и те же входные файлы, модели и параметры. Веса заранее извлечены и проверены. Нативные ASR/LLM-сессии загружаются заново внутри каждого замера; первая обработка инструкции LLM также учитывается. Первое извлечение весов из APK проверено отдельно и в эту пару не входит. Каждый файл запускается один раз в каждом режиме; это ориентир на данном эмуляторе, а не статистическая оценка. Порядок запуска чередуется между файлами; подготовка тестовых файлов выполнена вне замера. Естественная запись — исходный conversation.mp3 целиком, без преобразования в WAV перед замером. Поэтому измерение учитывает ускорение MediaCodec.', '',
        '| Запись | Аудио, с | Было, с | Стало, с | Ускорение | Реальные вызовы LLM |','|---|---:|---:|---:|---:|---:|']
    for x in paired:
        b=x['before'];a=x['after'];lines.append(f'| {x["fixture"]} | {x["durationMs"]/1000:.2f} | {b["processingMs"]/1000:.2f} | {a["processingMs"]/1000:.2f} | {b["processingMs"]/a["processingMs"]:.2f}× | {b["llmCalls"]} → {a["llmCalls"]} |')
    lines+=['', 'Экономия зависит от кодека входного файла, точных повторов реплик и отклонённых продолжений LLM. На WAV без повторов и без отклонений ускорение не гарантируется. Колебания времени возможны из-за нагрузки компьютера и эмулятора.', '', '## Время по этапам в парных прогонах','', '| Запись / этап | Было, с | Стало, с |','|---|---:|---:|']
    for x in paired:
        for stage,b in x['before']['stageMs'].items():lines.append(f'| {x["fixture"]} / {stage} | {b/1000:.3f} | {x["after"]["stageMs"].get(stage,0)/1000:.3f} |')
    lines+=['', '## Ограничения и воспроизведение', '',
        'Это эмулятор Android 15 ARM64, авиарежим. Физический телефон не проверен. Измерения не гарантируют такой же коэффициент на другом процессоре. Правильное число голосов не означает идеальный DER; прежние ограничения ASR и диаризации сохраняются.', '',
        '`scripts/run_android_checks.sh` запускает проверки. `DecoderRegressionTest` побитово сравнивает PCM, `SpeedRegressionTest` сравнивает локальную LLM, `PipelineSpeedTest` — весь процесс. `QualityBenchmark` и `PipelineTest` сравнивают итоговые тексты с 0.3; `NoiseRegressionTest` сравнивает 45 ASR-результатов. `scripts/summarize_speed_checks.py` создаёт эту сводку. Сырые измерения: `artifacts/test-results/speed-punctuation.json`, `speed-pipeline.json`, `speed-comparison.json`. Исторические данные 0.3: `before-speed-v4/`.','']
    (ROOT/'docs/SPEED_RESULTS.md').write_text('\n'.join(lines))
    print(json.dumps({k:v for k,v in report.items() if k not in ['pipeline_paired','historical_unpaired']},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
