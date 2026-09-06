module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');

  const gstin = (req.query && req.query.gstin ? String(req.query.gstin) : '').trim().toUpperCase();

  if (!gstin || gstin.length !== 15) {
    res.status(400).json({ ok: false, error: 'Enter a valid 15-character GSTIN' });
    return;
  }

  const apiKey = process.env.GSTIN_API_KEY;
  if (!apiKey) {
    res.status(500).json({ ok: false, error: 'GSTIN_API_KEY not configured on server' });
    return;
  }

  try {
    const upstream = await fetch(`https://www.gstinapi.in/v1/gstin/${gstin}`, {
      headers: { 'x-api-key': apiKey }
    });
    const data = await upstream.json();

    if (!upstream.ok || data.error) {
      res.status(upstream.status || 400).json({ ok: false, error: data.error || 'GSTIN not found' });
      return;
    }

    // gstinapi.in's official docs show flat snake_case fields (legal_name, trade_name,
    // address) — but we keep older camelCase variants as fallback too, in case the
    // live response differs from docs for some accounts/tiers.
    const payload = data.data || data; // unwrap if ever nested under "data"

    const addr = payload.pradr && payload.pradr.addr;
    const addrFromParts = addr ? [addr.bno, addr.st, addr.loc, addr.dst, addr.pncd].filter(Boolean).join(', ') : '';

    // GSTIN's first 2 digits are the official state code — most reliable source for state name.
    const STATE_CODES = {'01':'Jammu and Kashmir','02':'Himachal Pradesh','03':'Punjab','04':'Chandigarh','05':'Uttarakhand','06':'Haryana','07':'Delhi','08':'Rajasthan','09':'Uttar Pradesh','10':'Bihar','11':'Sikkim','12':'Arunachal Pradesh','13':'Nagaland','14':'Manipur','15':'Mizoram','16':'Tripura','17':'Meghalaya','18':'Assam','19':'West Bengal','20':'Jharkhand','21':'Odisha','22':'Chhattisgarh','23':'Madhya Pradesh','24':'Gujarat','26':'Dadra and Nagar Haveli and Daman and Diu','27':'Maharashtra','28':'Andhra Pradesh','29':'Karnataka','30':'Goa','31':'Lakshadweep','32':'Kerala','33':'Tamil Nadu','34':'Puducherry','35':'Andaman and Nicobar Islands','36':'Telangana','37':'Andhra Pradesh','38':'Ladakh'};

    // Normalize the fields the billing app's verifyAndFetchGSTIN() expects, trying every
    // field-name variant seen across GST API providers.
    res.status(200).json({
      ok: true,
      legalName: payload.legal_name || payload.legalName || payload.lgnm || payload.name || '',
      tradeName: payload.trade_name || payload.tradeName || payload.tradeNam || '',
      status: payload.status || payload.sts || '',
      state: payload.state || payload.state_jurisdiction || STATE_CODES[gstin.slice(0, 2)] || '',
      address: payload.address || payload.addr || addrFromParts || ''
    });
  } catch (e) {
    res.status(500).json({ ok: false, error: 'Verification service unavailable, try again' });
  }
};
