#!/usr/bin/env python3
"""Create public release assets without reading or including any real secret values."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile

root=Path(__file__).resolve().parent.parent
out=root/'dist/release';out.mkdir(parents=True,exist_ok=False)
release_dir=root/os.environ['RELEASE_DIR']
metadata=json.loads((release_dir/'release.json').read_text())
metadata.update(tag=os.environ['RELEASE_TAG'],commit=os.environ['COMMIT_SHA'],image=os.environ['IMAGE_DIGEST'],
                java='25',postgresql='16.4',platform='linux/amd64',
                workflow_run=os.environ.get('GITHUB_SERVER_URL','https://github.com')+'/'+os.environ['REPOSITORY']+'/actions/runs/'+os.environ['GITHUB_RUN_ID'],
                secrets_policy='Values are supplied at deployment. Use the named immutable Secret; retain old versions for rollback. Never attach credentials to a release.')
shutil.copy2(root/'scrapper/target/scrapper.jar',out/'scrapper.jar')
archives=list((root/'dist').glob('simplified-link-tracker-*.zip'))
assert len(archives)==1, 'Expected one versioned source archive'
shutil.copy2(archives[0],out/archives[0].name)
reports=list((root/'verification').rglob('runtime.json'))
assert len(reports)==1 and json.loads(reports[0].read_text())['success']
shutil.copy2(reports[0],out/'runtime.json')
with zipfile.ZipFile(out/'deployment.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for file in sorted(release_dir.iterdir()):
        assert file.name in {'configmap.yaml','migrate.yaml','web.yaml','worker.yaml','assign-legacy.yaml','postgresql.yaml','release.json'}
        archive.write(file,file.name)
    archive.write(root/'k8s/namespace.yaml','namespace.yaml')
    archive.write(root/'k8s/secret.env.example','secret.env.example')
metadata['assets_sha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(out.iterdir()) if f.is_file()}
(out/'release.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
(out/'SHA256SUMS').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in sorted(out.iterdir()) if f.is_file()))
(out/'notes.md').write_text(f'''Регистрация по email и паролю, личные коллекции ссылок, мониторинг GitHub и Stack Overflow.

- Коммит: `{metadata['commit']}`.
- Образ: `{metadata['image']}`; в развёртывании используется digest.
- Проверки: [GitHub Actions]({metadata['workflow_run']}); подробные результаты в `runtime.json`.
- `scrapper.jar` и контейнер прошли проверки до публикации; повторной сборки при публикации нет.
- `deployment.zip`: готовые манифесты, конфигурация релиза и ссылка на отдельный immutable Secret.
- `SHA256SUMS` позволяет проверить целостность файлов.

Порядок запуска и миграции: README и docs/Kubernetes.md в исходном архиве.
Секреты не входят в релиз. Запуск кластера и выбор рабочих значений выполняет оператор.
''')
