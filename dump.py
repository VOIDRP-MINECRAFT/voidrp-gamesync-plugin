from pathlib import Path
import argparse

EXCLUDED_DIRS = {".git", "gradle", ".gradle", "node_modules", "build", "target", "out", "dist", "venv", "__pycache__"}
# Расширения файлов, содержимое которых нужно записывать
CONTENT_EXTENSIONS = {".java", ".yml", ".yaml", ".json", ".xml", ".properties"}

def build_tree(root: Path, prefix: str = "", ignore_hidden: bool = False) -> list[str]:
    lines = []
    try:
        items = sorted(
            [
                item for item in root.iterdir()
                if item.name not in EXCLUDED_DIRS
            ],
            key=lambda p: (p.is_file(), p.name.lower())
        )
    except PermissionError:
        return [prefix + "[Нет доступа]"]

    if ignore_hidden:
        items = [item for item in items if not item.name.startswith(".")]

    for index, item in enumerate(items):
        is_last = index == len(items) - 1
        connector = "└── " if is_last else "├── "
        lines.append(prefix + connector + item.name)

        if item.is_dir():
            extension = "    " if is_last else "│   "
            lines.extend(build_tree(item, prefix + extension, ignore_hidden))

    return lines

def get_target_files(root: Path, extensions: set, ignore_hidden: bool = False) -> list[Path]:
    """Рекурсивно собирает список файлов с указанными расширениями"""
    files = []
    try:
        for item in root.iterdir():
            if item.name in EXCLUDED_DIRS:
                continue
            if ignore_hidden and item.name.startswith("."):
                continue
            
            if item.is_file() and item.suffix.lower() in extensions:
                files.append(item)
            elif item.is_dir():
                files.extend(get_target_files(item, extensions, ignore_hidden))
    except PermissionError:
        pass
    return files

def read_file_contents(files: list[Path]) -> list[str]:
    """Читает содержимое файлов и возвращает список строк"""
    lines = []
    lines.append("\n" + "=" * 60)
    lines.append("СОДЕРЖИМОЕ ФАЙЛОВ (.java, .yml)")
    lines.append("=" * 60 + "\n")
    
    for file_path in files:
        lines.append("-" * 60)
        lines.append(f"Файл: {file_path}")
        lines.append("-" * 60)
        
        try:
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                content = f.read()
                lines.append(content)
        except PermissionError:
            lines.append("[Нет доступа к файлу]\n")
        except Exception as e:
            lines.append(f"[Ошибка чтения файла: {e}]\n")
        
        lines.append("\n")
    
    return lines

def save_tree_to_file(start_path: str, output_file: str, ignore_hidden: bool = False, include_content: bool = False):
    root = Path(start_path).resolve()
    if not root.exists():
        print(f"Ошибка: путь не существует: {root}")
        return

    lines = [str(root)]
    lines.append("")
    lines.extend(build_tree(root, ignore_hidden=ignore_hidden))

    if include_content:
        files = get_target_files(root, CONTENT_EXTENSIONS, ignore_hidden)
        if files:
            lines.extend(read_file_contents(files))
            print(f"Найдено файлов для записи содержимого: {len(files)}")
        else:
            print("Файлы с указанными расширениями (.java, .yml) не найдены")

    with open(output_file, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"Иерархия сохранена в файл: {output_file}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Сохранить иерархию файлов и папок в txt-файл")
    parser.add_argument("path", nargs="?", default=".", help="Папка, для которой строить дерево")
    parser.add_argument("-o", "--output", default="file_tree.txt", help="Имя выходного txt-файла")
    parser.add_argument("--ignore-hidden", action="store_true", help="Игнорировать скрытые файлы и папки")
    parser.add_argument("--include-content", action="store_true", help="Включить содержимое файлов (.java, .yml) в вывод")
    args = parser.parse_args()

    save_tree_to_file(args.path, args.output, args.ignore_hidden, args.include_content)