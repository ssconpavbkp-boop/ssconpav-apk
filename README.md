# SSCONPAV · APK (app de campo)

App de campo da SSCONPAV Construtora — base do Funchal-Mobile, com tela inicial em ícones (estilo celular) e dock inferior.

- `index.html` — o app inteiro. Publicado em GitHub Pages: `https://ssconpavbkp-boop.github.io/ssconpav-apk/`
- `versao.json` — versão publicada; o app compara `appVersion` com a sua `APP_VERSION` e avisa quando há uma nova.
- `icon-192.png` / `icon-512.png` — ícones para a casca Android (APK) e atalho na tela inicial.

O app grava na mesma tabela `ssconpav_registros` e no mesmo bucket `ssconpav-fotos` do Software: é assim que os dois conversam.

Nuvem: URL e chave publishable do projeto SSCONPAV ficam em `const NUVEM` dentro de `index.html` — preencha antes de publicar.
