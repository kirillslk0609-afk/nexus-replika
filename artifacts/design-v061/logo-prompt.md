# Реплика 0.6.1 — утверждённый знак «Диалог»

Пользователь выбрал вариант с двумя сообщениями. Создан встроенным Imagegen, затем подготовлен отдельный ресурс по утверждённой картинке. Голубое сообщение сверху слева, сиреневое снизу справа. Текст презентации и миниатюры в ресурс не входят.

- Референс: `selected-dialogue.png`.
- Ресурс: `replica-dialogue.png`, точная копия в `app/src/main/res/drawable-nodpi/replica_mark.png`.
- Режим: built-in image_gen, edit, непрозрачный тёмный фон.
- Попытка прозрачного PNG с цветными артефактами отклонена и не включена в приложение.

## Финальный промпт

Use case: precise-object-edit / logo-brand. Approved reference is the «02 · Диалог» concept board. Deliver ONE final Android app icon foreground image, square. Reproduce precisely the two speech bubbles from its large central icon: sky blue upper-left, lavender lower-right, opposing small tails, clean dark gap between them. Same wide rounded-square silhouettes. Delete all words, duplicate miniature icons and the rounded background tile. Use a completely opaque, uniform solid charcoal #1D2228 background covering the entire square edge to edge, also filling the narrow separating gap. Center the two bubbles as a single unit occupying ONLY the middle 58% of canvas width and 44% of canvas height, with generous equal empty margins so Android circle and squircle masks never clip the tails. Flat blue #9CC9F4 and lavender #B9A2DA fills. Sharp smooth clean edges. Absolutely NO transparency, no alpha removal, no checkerboard, no colored halos, no speckles, no grain, no shadows, no gradient, no text, no frame. This is a finished clean app resource, not a concept presentation.
