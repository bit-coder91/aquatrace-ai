"use client";

import { useEffect, useMemo, useState } from "react";
import { createUserWithEmailAndPassword, onAuthStateChanged, signInWithEmailAndPassword, signOut, type User } from "firebase/auth";
import { Activity, ArrowUpRight, Building2, Check, Droplets, LogOut, Plus, Upload, Waves } from "lucide-react";
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { z } from "zod";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { auth } from "@/lib/firebase";
import { Button } from "@/components/ui/button";
import { api, type Alert, type DailyAggregate, type IngestionJob, type Meter, type Site } from "@/lib/api";

const siteSchema = z.object({ name: z.string().trim().min(1, "Enter a building name.").max(120) });
const meterSchema = z.object({
  label: z.string().trim().min(1, "Enter a meter label.").max(100),
  unitId: z.string().trim().min(1, "Enter a unit identifier.").max(80),
  meterType: z.enum(["MAIN", "SUBMETER"]),
  unitOfMeasure: z.enum(["L", "M3"]),
  readingMode: z.enum(["CUMULATIVE", "INTERVAL"]),
  highUsageLitresPerHour: z.string().optional().transform((value) => value?.trim() ? Number(value) : undefined).refine((value) => value === undefined || (Number.isFinite(value) && value > 0), "Use a positive threshold."),
  lowUsageLitresPerHour: z.string().optional().transform((value) => value?.trim() ? Number(value) : undefined).refine((value) => value === undefined || (Number.isFinite(value) && value > 0), "Use a positive threshold."),
  lowWindowStart: z.string().optional().refine((value) => !value || /^([01]\d|2[0-3]):[0-5]\d$/.test(value), "Use HH:MM."),
  lowWindowEnd: z.string().optional().refine((value) => !value || /^([01]\d|2[0-3]):[0-5]\d$/.test(value), "Use HH:MM."),
  sustainedIntervals: z.string().optional().transform((value) => value?.trim() ? Number(value) : undefined).refine((value) => value === undefined || (Number.isInteger(value) && value >= 2 && value <= 24), "Use an integer from 2 to 24."),
}).superRefine((values, context) => {
  if (values.lowUsageLitresPerHour !== undefined && (!values.lowWindowStart || !values.lowWindowEnd)) {
    context.addIssue({ code: "custom", path: ["lowWindowStart"], message: "Add both low-use window times when setting a low-use threshold." });
  }
});

export default function Home() {
  const [user, setUser] = useState<User | null>(null);
  const [sites, setSites] = useState<Site[]>([]);
  const [jobs, setJobs] = useState<IngestionJob[]>([]);
  const [meters, setMeters] = useState<Meter[]>([]);
  const [aggregates, setAggregates] = useState<DailyAggregate[]>([]);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [siteId, setSiteId] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const siteForm = useForm<z.infer<typeof siteSchema>>({ resolver: zodResolver(siteSchema) });
  const meterForm = useForm<z.input<typeof meterSchema>, unknown, z.output<typeof meterSchema>>({ resolver: zodResolver(meterSchema), defaultValues: { meterType: "SUBMETER", unitOfMeasure: "L", readingMode: "CUMULATIVE" } });

  useEffect(() => onAuthStateChanged(auth, setUser), []);
  useEffect(() => {
    if (!user) return;
    api<Site[]>("/api/v1/sites").then((data) => {
      setSites(data);
      setSiteId((current) => current || data[0]?.siteId || "");
    }).catch((e) => setError(e.message));
  }, [user]);
  useEffect(() => {
    if (!siteId || !user) return;
    const today = new Date();
    const from = new Date(today);
    from.setDate(today.getDate() - 13);
    const params = new URLSearchParams({ from: from.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10) });
    Promise.allSettled([
      api<IngestionJob[]>(`/api/v1/sites/${siteId}/ingestion-jobs`),
      api<Meter[]>(`/api/v1/sites/${siteId}/meters`),
      api<DailyAggregate[]>(`/api/v1/sites/${siteId}/daily-aggregates?${params}`),
      api<Alert[]>(`/api/v1/sites/${siteId}/alerts?status=OPEN`),
    ]).then((results) => {
      if (results[0].status === "fulfilled") setJobs(results[0].value);
      if (results[1].status === "fulfilled") setMeters(results[1].value);
      if (results[2].status === "fulfilled") setAggregates(results[2].value);
      if (results[3].status === "fulfilled") setAlerts(results[3].value);
      const failed = results.find((result) => result.status === "rejected");
      if (failed?.status === "rejected") setError(failed.reason instanceof Error ? failed.reason.message : "Some site data could not be loaded.");
    });
  }, [siteId, user]);

  async function action(run: () => Promise<void>) {
    setBusy(true); setError("");
    try { await run(); } catch (e) { setError(e instanceof Error ? e.message : "Something went wrong."); }
    finally { setBusy(false); }
  }
  const selected = sites.find((site) => site.siteId === siteId);
  const latestUsage = aggregates.at(-1)?.totalUsageLitres;
  const enabledMeters = meters.filter((meter) => meter.enabled);
  const reportingMeters = enabledMeters.length;
  const nonCurrentMeters = enabledMeters.filter((meter) => meter.freshness !== "CURRENT").length;
  const quality = jobs.find((job) => job.acceptedRows != null);
  const acceptance = quality && quality.inputRows ? Math.round((quality.acceptedRows! / quality.inputRows) * 100) : null;
  const chartData = useMemo(() => aggregates.map((day) => ({ ...day, label: new Date(`${day.date}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" }) })), [aggregates]);
  const canManage = selected?.role !== "VIEWER";

  if (!user) return <main className="auth-shell"><section className="auth-card"><div className="brand"><span className="brand-mark"><Waves size={21}/></span><span>AQUATRACE</span></div><p className="eyebrow">WATER INTELLIGENCE PLATFORM</p><h1>Every drop,<br/>accounted for.</h1><p className="muted">See building usage clearly. Catch unusual flow early. Make every investigation data led.</p><div className="auth-form"><label>Email address<input type="email" autoComplete="email" value={email} onChange={(e)=>setEmail(e.target.value)} placeholder="you@building.com"/></label><label>Password<input type="password" autoComplete="current-password" value={password} onChange={(e)=>setPassword(e.target.value)} placeholder="At least 6 characters"/></label><button className="button primary full" disabled={busy||!email||!password} onClick={()=>action(async()=>{await signInWithEmailAndPassword(auth,email,password);})}>Sign in</button><button className="text-button" disabled={busy||!email||password.length<6} onClick={()=>action(async()=>{await createUserWithEmailAndPassword(auth,email,password);})}>New to AquaTrace? Create an account</button>{error&&<p className="error">{error}</p>}</div><p className="auth-foot">Protected by Firebase Authentication · Rules based, never claims a confirmed leak</p></section><aside className="auth-art"><div className="orb orb-one"/><div className="orb orb-two"/><div className="art-copy"><span>LIVE WATER SIGNAL</span><strong>Small signals.<br/>Clear decisions.</strong><p>Usage patterns that help your team know what to check next.</p></div><div className="art-stat"><Droplets/><div><small>BUILDING INSIGHT</small><b>Water, made visible</b></div><ArrowUpRight/></div></aside></main>;

  return <main className="dashboard"><header className="topbar"><div className="brand"><span className="brand-mark"><Waves size={21}/></span><span>AQUATRACE</span></div><div className="top-actions"><span className="user-chip">{user.email}</span><button className="icon-button" title="Sign out" onClick={()=>signOut(auth)}><LogOut size={17}/></button></div></header><div className="content"><div className="heading-row"><div><p className="eyebrow">OVERVIEW / {selected?.name?.toUpperCase() ?? "YOUR SITES"}</p><h1>Water intelligence</h1><p className="muted">Understand your building’s usage and what deserves a closer look.</p></div><div className="site-select"><Building2 size={17}/><select value={siteId} onChange={(e)=>setSiteId(e.target.value)} aria-label="Select site"><option value="">Select a site</option>{sites.map((site)=><option key={site.siteId} value={site.siteId}>{site.name}</option>)}</select></div></div>
    {!sites.length&&<section className="empty-site panel"><span className="panel-icon"><Building2/></span><h2>Start with your first building</h2><p className="muted">Create a site, then add meters and upload readings.</p><form className="inline-form" onSubmit={siteForm.handleSubmit((values)=>action(async()=>{const site=await api<Site>("/api/v1/sites",{method:"POST",body:JSON.stringify({name:values.name,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone})});setSites([site]);setSiteId(site.siteId);siteForm.reset();}))}><input {...siteForm.register("name")} placeholder="Building or site name" aria-invalid={!!siteForm.formState.errors.name}/><Button disabled={busy}><Plus size={16}/> Create site</Button></form>{siteForm.formState.errors.name&&<p className="error">{siteForm.formState.errors.name.message}</p>}</section>}
    {!!sites.length&&<><div className="metric-grid"><article className="metric panel"><div className="metric-top"><span>Daily water use</span><Droplets size={18}/></div><strong>{latestUsage==null?"—":`${Math.round(latestUsage).toLocaleString()} L`}</strong><small>{aggregates.length?`Latest aggregate · ${aggregates.at(-1)?.date}`:"Waiting for pipeline aggregates"}</small></article><article className="metric panel"><div className="metric-top"><span>Active meters</span><Activity size={18}/></div><strong>{reportingMeters||"—"}</strong><small>{meters.length?`${nonCurrentMeters} stale or without readings`:"No meters registered yet"}</small></article><article className="metric panel"><div className="metric-top"><span>Open investigations</span><Waves size={18}/></div><strong>{alerts.length}</strong><small>Rules-based signals to review</small></article><article className="metric panel"><div className="metric-top"><span>Latest file quality</span><Check size={18}/></div><strong>{acceptance==null?"—":`${acceptance}%`}</strong><small>{quality?`${quality.rejectedRows??0} rejected of ${quality.inputRows??0} rows`:"Awaiting a pipeline run"}</small></article></div>
    <section className="panel chart-panel"><div className="section-heading"><div><span className="eyebrow">CONSUMPTION TREND</span><h2>Daily building use</h2></div><span className="count-pill">Last 14 days</span></div>{chartData.length?<ResponsiveContainer width="100%" height={250}><AreaChart data={chartData} margin={{top:12,right:12,bottom:0,left:0}}><defs><linearGradient id="usageFill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#168e82" stopOpacity={0.24}/><stop offset="95%" stopColor="#168e82" stopOpacity={0.01}/></linearGradient></defs><CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#eaf0ed"/><XAxis dataKey="label" axisLine={false} tickLine={false} tick={{fontSize:11,fill:"#829493"}}/><YAxis axisLine={false} tickLine={false} tick={{fontSize:10,fill:"#829493"}} width={48}/><Tooltip formatter={(value)=>[`${Number(value).toLocaleString()} L`,"Daily use"]}/><Area type="monotone" dataKey="totalUsageLitres" stroke="#087e78" strokeWidth={2.5} fill="url(#usageFill)"/></AreaChart></ResponsiveContainer>:<div className="chart-empty">Daily trend appears after the batch pipeline loads curated aggregates.</div>}</section>
    {canManage&&<section className="panel upload-panel"><div><span className="eyebrow">DATA INTAKE</span><h2>Bring your readings in</h2><p className="muted">Upload a CSV or JSON export. Original files are retained for traceability.</p></div><form onSubmit={(e)=>{e.preventDefault();void action(async()=>{if(!file||!siteId)return;const data=new FormData();data.append("file",file);const job=await api<IngestionJob>(`/api/v1/sites/${siteId}/ingestion-jobs`,{method:"POST",body:data});setJobs((current)=>[job,...current]);setFile(null);});}}><label className="file-picker"><Upload size={17}/><span>{file?.name??"Choose a CSV or JSON file"}</span><input type="file" accept=".csv,.json,.jsonl" onChange={(e)=>setFile(e.target.files?.[0]??null)}/></label><Button disabled={busy||!file}><Upload size={16}/> Upload file</Button></form></section>}
    {canManage&&<section className="panel meter-panel"><div><span className="eyebrow">METER INVENTORY</span><h2>Register a meter</h2><p className="muted">Set the meter's identifier, reading mode, and optional investigation thresholds.</p></div><form className="meter-registration-form" onSubmit={meterForm.handleSubmit((values)=>action(async()=>{const {highUsageLitresPerHour,lowUsageLitresPerHour,lowWindowStart,lowWindowEnd,sustainedIntervals,...meter}=values;const thresholds={highUsageLitresPerHour,lowUsageLitresPerHour,lowWindowStart:lowWindowStart||undefined,lowWindowEnd:lowWindowEnd||undefined,sustainedIntervals};await api(`/api/v1/sites/${siteId}/meters`,{method:"POST",body:JSON.stringify({...meter,thresholds,expectedIntervalMinutes:60,installationDate:new Date().toISOString().slice(0,10)})});const next=await api<Meter[]>(`/api/v1/sites/${siteId}/meters`);setMeters(next);meterForm.reset(undefined,{keepDefaultValues:true});}))}><input {...meterForm.register("label")} placeholder="Meter label (e.g. Unit 101)" aria-invalid={!!meterForm.formState.errors.label}/><input {...meterForm.register("unitId")} placeholder="Unit ID (e.g. 101)" aria-invalid={!!meterForm.formState.errors.unitId}/><select {...meterForm.register("meterType")} aria-label="Meter type"><option value="SUBMETER">Submeter</option><option value="MAIN">Main meter</option></select><select {...meterForm.register("unitOfMeasure")} aria-label="Meter unit"><option value="L">Litres</option><option value="M3">Cubic metres</option></select><select {...meterForm.register("readingMode")} aria-label="Reading mode"><option value="CUMULATIVE">Cumulative</option><option value="INTERVAL">Interval use</option></select><input type="number" step="any" {...meterForm.register("highUsageLitresPerHour")} placeholder="High-use L/hour (optional)"/><input type="number" step="any" {...meterForm.register("lowUsageLitresPerHour")} placeholder="Low-window L/hour (optional)"/><input type="time" {...meterForm.register("lowWindowStart")} aria-label="Low-use window start"/><input type="time" {...meterForm.register("lowWindowEnd")} aria-label="Low-use window end"/><input type="number" min="2" max="24" {...meterForm.register("sustainedIntervals")} placeholder="Sustained intervals"/><Button disabled={busy}><Plus size={16}/> Add meter</Button></form>{Object.values(meterForm.formState.errors).length>0&&<p className="error">{Object.values(meterForm.formState.errors).map((item)=>item?.message).filter((message): message is string=>typeof message==="string").join(" ")}</p>}<div className="meter-list">{meters.map((meter)=><div className="meter-item" key={meter.meterId}><div><b>{meter.label}</b><small>{meter.unitId} · {meter.meterType} · {meter.freshness??"NO_DATA"}</small></div><code title="Use this ID in imported readings">{meter.meterId}</code></div>)}</div></section>}
    <section className="panel alerts-panel"><div className="section-heading"><div><span className="eyebrow">INVESTIGATIONS</span><h2>Signals to review</h2></div><span className="count-pill">{alerts.length} open</span></div>{alerts.length?<div className="alert-list">{alerts.map((alert)=><article className="alert-row" key={alert.alertId}><div className="alert-severity">!</div><div className="alert-body"><div className="alert-title"><b>{alert.type?.replaceAll("_"," ")??"Usage signal"}</b><span className={`severity severity-${alert.severity?.toLowerCase()}`}>{alert.severity}</span></div><p>{alert.explanation}</p><small>{alert.recommendedInvestigation} · {alert.detectedAt?new Date(alert.detectedAt).toLocaleString():""}</small></div>{canManage&&<Button variant="secondary" disabled={busy} onClick={()=>action(async()=>{await api(`/api/v1/sites/${siteId}/alerts/${alert.alertId}/acknowledge`,{method:"PATCH"});setAlerts((items)=>items.filter((item)=>item.alertId!==alert.alertId));})}>Acknowledge</Button>}</article>)}</div>:<div className="empty-jobs"><span className="panel-icon"><Check/></span><b>No open signals</b><span className="muted">Rules-based anomalies will show up here for review.</span></div>}</section>
    <section className="panel jobs-panel"><div className="section-heading"><div><span className="eyebrow">RECENT ACTIVITY</span><h2>Ingestion jobs</h2></div><span className="count-pill">{jobs.length} jobs</span></div>{jobs.length?<div className="job-list">{jobs.map((job)=><div className="job-row" key={job.jobId}><div className="job-icon"><Upload size={16}/></div><div className="job-name"><b>{job.fileName||"Data upload"}</b><small>{job.createdAt?new Date(job.createdAt).toLocaleString():job.jobId}</small></div><span className={`status status-${job.status?.toLowerCase()}`}>{job.status}</span><span className="job-count">{job.acceptedRows==null?"Awaiting pipeline":`${job.acceptedRows} accepted · ${job.rejectedRows??0} rejected`}</span></div>)}</div>:<div className="empty-jobs"><span className="panel-icon"><Upload/></span><b>No uploads yet</b><span className="muted">Your files and pipeline results will appear here.</span></div>}</section><footer>Water signals are decision support. An alert means investigate; it does not confirm a leak.</footer></>}
    {error&&<div className="toast error">{error}</div>}</div></main>;
}







