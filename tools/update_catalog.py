# Run after adding or replacing a theme zip: fills in each theme's "size" and "sha256" in catalog.json
# from the file it points to (the Y1 checks both before installing).
#   python tools/update_catalog.py
import hashlib, json, os

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
path = os.path.join(root, 'catalog.json')
catalog = json.load(open(path, encoding='utf-8'))
for theme in catalog['themes']:
    data = open(os.path.join(root, theme['file']), 'rb').read()
    theme['size'] = len(data)
    theme['sha256'] = hashlib.sha256(data).hexdigest()
    print('%-22s %8d  %s' % (theme['name'], theme['size'], theme['sha256']))
with open(path, 'w', encoding='utf-8', newline='\n') as f:
    json.dump(catalog, f, ensure_ascii=False, indent=2)
    f.write('\n')
