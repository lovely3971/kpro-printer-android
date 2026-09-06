module.exports = function handler(req, res) {
  const h = req.headers || {};
  const dec = (v) => { try { return decodeURIComponent(v || ''); } catch { return v || ''; } };
  const rawIp = h['x-forwarded-for'] || h['x-real-ip'] || '';
  const ip = String(rawIp).split(',')[0].trim();
  res.setHeader('Cache-Control', 'no-store');
  res.status(200).json({
    city: dec(h['x-vercel-ip-city']),
    state: dec(h['x-vercel-ip-country-region']),
    country: dec(h['x-vercel-ip-country']),
    ip
  });
};
