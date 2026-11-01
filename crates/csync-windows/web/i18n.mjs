export function createTranslator(catalog, locale) {
  const language = catalog[locale];
  const message = (value, depth = 0) => {
    if (depth > 8 || typeof value !== 'string') return String(value ?? '');
    let parsed;
    try { parsed = JSON.parse(value); } catch { return value; }
    if (!parsed || typeof parsed.csyncKey !== 'string') return value;
    return translate(parsed.csyncKey, parsed.args ?? {}, depth + 1);
  };
  const translate = (key, args = {}, depth = 0) => {
    let template = language?.messages?.[key];
    if (template && typeof template === 'object') {
      const count = Number(args.count);
      let category = 'other';
      if (Number.isFinite(count)) {
        try { category = new Intl.PluralRules(locale).select(count); } catch { /* test locale */ }
      }
      template = template[category] || template.other;
    }
    if (typeof template !== 'string' || !template.length) return key;
    return template.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (match, name) =>
      Object.hasOwn(args, name) ? message(args[name], depth + 1) : match);
  };
  return { t: translate, message };
}
