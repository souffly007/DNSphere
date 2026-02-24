package fr.bonobo.dnsphere

import android.content.Context
import fr.bonobo.dnsphere.data.AppDatabase
import kotlinx.coroutines.runBlocking

class BlockListManager(private val context: Context) {

    private val adDomains = mutableSetOf<String>()
    private val trackerDomains = mutableSetOf<String>()
    private val malwareDomains = mutableSetOf<String>()
    private val customDomains = mutableSetOf<String>()
    private val whitelistedDomains = mutableSetOf<String>()

    private val database = AppDatabase.getInstance(context)

    init {
        loadDefaultLists()
        loadCustomLists()
        loadWhitelist()
    }

    private fun loadDefaultLists() {
        loadAdDomains()
        loadTrackerDomains()
        loadMalwareDomains()
    }

    private fun loadAdDomains() {
        adDomains.addAll(listOf(
            // =====================================================
            // GOOGLE ADS
            // =====================================================
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "adservice.google.com",
            "adservice.google.fr",
            "adservice.google.de",
            "adservice.google.co.uk",
            "www.googleadservices.com",
            "googleadservices.com",
            "ad.doubleclick.net",
            "ads.google.com",
            "adclick.g.doubleclick.net",
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com",
            "www.googletagservices.com",
            "googletagservices.com",
            "partner.googleadservices.com",
            "redirector.gvt1.com",
            "vid.googlesyndication.com",
            "s0.2mdn.net",
            "z2mdn.net",
            "ad.mo.doubleclick.net",
            "ad-g.doubleclick.net",
            "fwmrm.net",
            "g.doubleclick.net",
            "ade.googlesyndication.com",
            "adx.g.doubleclick.net",
            "cm.g.doubleclick.net",
            "dart.l.doubleclick.net",
            "m.doubleclick.net",
            "mediavisor.doubleclick.net",
            "pubads.g.doubleclick.net",
            "static.doubleclick.net",

            // =====================================================
            // FACEBOOK / META ADS
            // =====================================================
            "an.facebook.com",
            "ads.facebook.com",
            "www.facebook.com/ads",
            "pixel.facebook.com",
            "ad.facebook.com",
            "ads.instagram.com",
            "business.facebook.com",
            "facebook.net",
            "fbads.com",

            // =====================================================
            // TABOOLA (Pubs in-article très courantes)
            // =====================================================
            "taboola.com",
            "www.taboola.com",
            "cdn.taboola.com",
            "trc.taboola.com",
            "api.taboola.com",
            "vidstat.taboola.com",
            "images.taboola.com",
            "nr.taboola.com",
            "popup.taboola.com",
            "cdn2.taboola.com",
            "rules.taboola.com",
            "taboolasyndication.com",
            "tblcdn.com",

            // =====================================================
            // OUTBRAIN (Recommandations sponsorisées)
            // =====================================================
            "outbrain.com",
            "www.outbrain.com",
            "widgets.outbrain.com",
            "odb.outbrain.com",
            "log.outbrain.com",
            "images.outbrain.com",
            "amplify.outbrain.com",
            "tr.outbrain.com",
            "paid.outbrain.com",
            "outbrainimg.com",
            "sphere.com",

            // =====================================================
            // REVCONTENT
            // =====================================================
            "revcontent.com",
            "www.revcontent.com",
            "cdn.revcontent.com",
            "trends.revcontent.com",
            "assets.revcontent.com",
            "labs-cdn.revcontent.com",
            "img.revcontent.com",

            // =====================================================
            // MGID
            // =====================================================
            "mgid.com",
            "www.mgid.com",
            "servicer.mgid.com",
            "cdn.mgid.com",
            "dt.mgid.com",
            "jsc.mgid.com",
            "img.mgid.com",

            // =====================================================
            // TEADS (Pubs vidéo in-article)
            // =====================================================
            "teads.tv",
            "www.teads.tv",
            "cdn.teads.tv",
            "cdn2.teads.tv",
            "t.teads.tv",
            "a.teads.tv",
            "r.teads.tv",
            "teads.com",

            // =====================================================
            // CONTENT.AD / ZERGNET
            // =====================================================
            "content.ad",
            "api.content.ad",
            "zergnet.com",
            "www.zergnet.com",

            // =====================================================
            // SHARETHROUGH / NATIVO
            // =====================================================
            "sharethrough.com",
            "native.sharethrough.com",
            "stx.sharethrough.com",
            "nativo.com",
            "www.nativo.com",
            "s.nativo.com",
            "sdk.nativo.com",

            // =====================================================
            // TRIPLELIFT
            // =====================================================
            "triplelift.com",
            "eb2.3lift.com",
            "tlx.3lift.com",
            "3lift.com",
            "ib.3lift.com",
            "img-cdn.3lift.com",

            // =====================================================
            // PRIMIS (Pubs vidéo)
            // =====================================================
            "primis.tech",
            "live.primis.tech",
            "cdn.primis.tech",
            "va.primis.tech",

            // =====================================================
            // ADTHRIVE / MEDIAVINE (Blogs)
            // =====================================================
            "ads.adthrive.com",
            "adthrive.com",
            "video.mediavine.com",
            "scripts.mediavine.com",
            "mediavine.com",
            "www.mediavine.com",
            "dashboard.mediavine.com",
            "ads.mediavine.com",

            // =====================================================
            // EZOIC
            // =====================================================
            "ezoic.net",
            "ezoic.com",
            "g.ezoic.net",
            "ezodn.com",
            "go.ezoic.net",
            "moatads.com",

            // =====================================================
            // AMAZON ADS
            // =====================================================
            "amazon-adsystem.com",
            "aax.amazon-adsystem.com",
            "fls-na.amazon-adsystem.com",
            "aan.amazon.com",
            "assoc-amazon.com",
            "aax-eu.amazon.com",
            "aax-fe.amazon.com",
            "aax-us-east.amazon.com",
            "advertising.amazon.com",
            "mads.amazon.com",
            "z-na.amazon-adsystem.com",

            // =====================================================
            // CRITEO
            // =====================================================
            "criteo.com",
            "www.criteo.com",
            "criteo.net",
            "sslwidget.criteo.com",
            "dis.criteo.com",
            "rta.criteo.com",
            "bidder.criteo.com",
            "static.criteo.net",
            "cas.criteo.com",
            "gum.criteo.com",

            // =====================================================
            // AUTRES RÉSEAUX PUBLICITAIRES
            // =====================================================
            // AdRoll
            "adroll.com",
            "d.adroll.com",
            "s.adroll.com",
            "app.adroll.com",

            // AdNxs / Xandr
            "adnxs.com",
            "ib.adnxs.com",
            "secure.adnxs.com",
            "acdn.adnxs.com",
            "prebid.adnxs.com",

            // Adsrvr / TradeDesk
            "adsrvr.org",
            "match.adsrvr.org",
            "insight.adsrvr.org",

            // AdForm
            "adform.net",
            "adform.com",
            "track.adform.net",
            "serving.adform.net",

            // PubMatic
            "pubmatic.com",
            "ads.pubmatic.com",
            "image2.pubmatic.com",
            "image4.pubmatic.com",
            "image6.pubmatic.com",
            "simage2.pubmatic.com",

            // Rubicon
            "rubiconproject.com",
            "fastlane.rubiconproject.com",
            "pixel.rubiconproject.com",
            "optimized-by.rubiconproject.com",

            // OpenX
            "openx.net",
            "openx.com",
            "servedbyopenx.com",
            "u.openx.net",
            "rtb.openx.net",

            // Index Exchange
            "indexww.com",
            "casalemedia.com",

            // Sovrn / Lijit
            "sovrn.com",
            "lijit.com",
            "ap.lijit.com",

            // Yahoo / Oath
            "advertising.com",
            "ads.yahoo.com",
            "adtech.de",
            "adtechus.com",

            // Verizon Media
            "ads.verizonmedia.com",

            // AppNexus
            "appnexus.com",
            "adserver.appnexus.com",

            // Smart AdServer
            "smartadserver.com",
            "www.smartadserver.com",
            "www2.smartadserver.com",
            "www3.smartadserver.com",
            "sascdn.com",
            "diff.smartadserver.com",
            "ced.sascdn.com",

            // Weborama
            "weborama.fr",
            "weborama.com",
            "weborama.net",
            "solution.weborama.fr",

            // AdUx / Adverline
            "adux.com",
            "adverline.com",

            // Ligatus
            "ligatus.com",
            "ligatus.fr",
            "ligadx.com",

            // Sizmek
            "sizmek.com",
            "bs.serving-sys.com",
            "ds.serving-sys.com",

            // DoubleVerify / IAS
            "doubleverify.com",
            "cdn.doubleverify.com",
            "integralads.com",
            "pixel.adsafeprotected.com",

            // MOAT
            "moat.com",
            "moatads.com",
            "z.moatads.com",
            "px.moatads.com",

            // =====================================================
            // MOBILE AD NETWORKS
            // =====================================================
            "admob.com",
            "googleadmobadssdk.com",

            "unityads.unity3d.com",
            "unityads.co",
            "ads.unity3d.com",

            "applovin.com",
            "applvn.com",
            "pxl.applovin.com",

            "vungle.com",
            "api.vungle.com",
            "cdn.vungle.com",

            "chartboost.com",
            "ads.chartboost.com",
            "live.chartboost.com",

            "mopub.com",
            "ads-mopub.com",

            "inmobi.com",
            "sdkapi.inmobi.com",

            "startappexchange.com",
            "startapp.com",

            "fyber.com",
            "ad.fyber.com",
            "ia.fyber.com",

            "ironsrc.com",
            "ironsource.com",
            "supersonicads.com",

            "adcolony.com",
            "adc3-launch.adcolony.com",
            "events.adcolony.com",
            "wd.adcolony.com",

            "tapjoy.com",
            "ads.tapjoy.com",
            "rpc.tapjoy.com",

            "digitalturbine.com",
            "ads.digitalturbine.com",

            "liftoff.io",
            "impression.liftoff.io",

            "mintegral.com",
            "cdn.mintegral.com",

            "bytedance.com",
            "ads.tiktok.com",
            "pangleglobal.com",

            // =====================================================
            // PUBS POPUP / INTERSTITIELS
            // =====================================================
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "propellerpops.com",
            "popmyads.com",
            "popup.taboola.com",
            "popunder.net",
            "clickadu.com",
            "exoclick.com",
            "exosrv.com",
            "juicyads.com",
            "trafficstars.com",
            "trafficforce.com",
            "plugrush.com",
            "adcash.com",
            "hilltopads.net",

            // =====================================================
            // RICH MEDIA / VIDEO ADS
            // =====================================================
            "innovid.com",
            "serving.innovid.com",
            "flashtalking.com",
            "servedby.flashtalking.com",
            "celtra.com",
            "ads.celtra.com",
            "samba.tv",
            "spotxchange.com",
            "spotx.tv",
            "springserve.com",
            "vidazoo.com",
            "connatix.com",
            "ex.co",
            "bounceexchange.com",

            // =====================================================
            // RETARGETING / DMP
            // =====================================================
            "rlcdn.com",
            "rfihub.com",
            "exelator.com",
            "tapad.com",
            "pippio.com",
            "liveramp.com",
            "adsymptotic.com",
            "adelixir.com",
            "intentiq.com",
            "blis.com",

            // =====================================================
            // AD VERIFICATION / FRAUD
            // =====================================================
            "grapeshot.co.uk",
            "peer39.com",
            "peer39.net",
            "adlooxtracking.com",
            "meetrics.net",
            "pixalate.com",
            "protected.com",
            "themediatrust.com",
            "confiant.com",
            "geoedge.be",

            // =====================================================
            // AFFILIATE / CPA NETWORKS
            // =====================================================
            "cj.com",
            "commission-junction.com",
            "emjcd.com",
            "awin.com",
            "awin1.com",
            "zanox.com",
            "linksynergy.com",
            "shareasale.com",
            "pjtra.com",
            "pjatr.com",
            "pntra.com",
            "pntrac.com",
            "pntrs.com",
            "flexoffers.com",
            "clickbank.net",
            "jdoqocy.com",
            "tkqlhce.com",
            "lduhtrp.net",
            "anrdoezrs.net",
            "kqzyfj.com",
            "dpbolvw.net",
            "tqlkg.com",
            "afcyhf.com",
            "awltovhc.com",
            "ftjcfx.com",
            "yceml.net",
            "pxf.io",
            "go.skimresources.com",
            "skimlinks.com",
            "redirectingat.com",
            "viglink.com",

            // =====================================================
            // PUBS FRANÇAISES
            // =====================================================
            "adotmob.com",
            "criteo.fr",
            "fidzup.com",
            "sirdata.com",
            "tradelab.com",
            "hi-media.com",
            "adux.fr",
            "numberly.com",
            "1000mercis.com",
            "ads.aufeminin.com",
            "ads.leparisien.fr",
            "ads.lemonde.fr"
        ))
    }

    private fun loadTrackerDomains() {
        trackerDomains.addAll(listOf(
            // =====================================================
            // GOOGLE ANALYTICS & TRACKING
            // =====================================================
            "google-analytics.com",
            "www.google-analytics.com",
            "ssl.google-analytics.com",
            "analytics.google.com",
            "googletagmanager.com",
            "www.googletagmanager.com",
            "tagmanager.google.com",
            "googleoptimize.com",
            "optimize.google.com",
            "gtm.googleadservices.com",
            "firebaselogging.googleapis.com",
            "app-measurement.com",
            "crashlytics.com",

            // =====================================================
            // FACEBOOK TRACKING
            // =====================================================
            "pixel.facebook.com",
            "connect.facebook.net",
            "graph.facebook.com",
            "fbevents.com",
            "analytics.facebook.com",
            "fbcdn.net",

            // =====================================================
            // ANALYTICS PLATFORMS
            // =====================================================
            "mixpanel.com",
            "cdn.mixpanel.com",
            "api.mixpanel.com",
            "decide.mixpanel.com",

            "amplitude.com",
            "api.amplitude.com",
            "cdn.amplitude.com",
            "analytics.amplitude.com",

            "segment.io",
            "segment.com",
            "api.segment.io",
            "cdn.segment.com",
            "events.segment.io",

            "heap.io",
            "heapanalytics.com",
            "cdn.heapanalytics.com",

            "pendo.io",
            "cdn.pendo.io",
            "app.pendo.io",

            "indicative.com",
            "countly.com",
            "plausible.io",

            // =====================================================
            // HEATMAPS / SESSION RECORDING
            // =====================================================
            "hotjar.com",
            "vars.hotjar.com",
            "script.hotjar.com",
            "static.hotjar.com",
            "insights.hotjar.com",

            "fullstory.com",
            "rs.fullstory.com",

            "mouseflow.com",
            "o2.mouseflow.com",

            "crazyegg.com",
            "script.crazyegg.com",
            "dnn506yrbagrg.cloudfront.net",

            "clicktale.com",
            "clicktale.net",
            "cdnssl.clicktale.net",

            "luckyorange.com",
            "luckyorange.net",

            "inspectlet.com",
            "cdn.inspectlet.com",

            "smartlook.com",
            "rec.smartlook.com",

            "logrocket.com",
            "cdn.logrocket.io",

            "clarity.ms",
            "www.clarity.ms",

            // =====================================================
            // MOBILE ATTRIBUTION
            // =====================================================
            "appsflyer.com",
            "api.appsflyer.com",
            "sdk.appsflyer.com",
            "events.appsflyer.com",
            "launches.appsflyer.com",

            "adjust.com",
            "app.adjust.com",
            "app.adjust.io",
            "s2s.adjust.com",
            "img.adjust.com",

            "branch.io",
            "api.branch.io",
            "cdn.branch.io",
            "app.link",
            "bnc.lt",

            "kochava.com",
            "control.kochava.com",
            "tracker.kochava.com",

            "singular.net",
            "singularcdn.net",
            "config.singular.net",

            "mparticle.com",
            "jssdks.mparticle.com",

            "tenjin.io",
            "tenjin.com",

            "apsalar.com",

            // =====================================================
            // CUSTOMER DATA / MARKETING
            // =====================================================
            "braze.com",
            "sdk.braze.com",
            "sdk.iad-01.braze.com",
            "sdk.iad-03.braze.com",
            "appboy.com",
            "appboy-images.com",

            "onesignal.com",
            "cdn.onesignal.com",
            "api.onesignal.com",

            "clevertap.com",
            "clevertap-prod.com",
            "wzrkt.com",

            "leanplum.com",
            "api.leanplum.com",
            "dev.leanplum.com",

            "airship.com",
            "urbanairship.com",
            "device-api.urbanairship.com",

            "pushwoosh.com",
            "cp.pushwoosh.com",

            "batch.com",
            "batch-cdn.com",

            "intercom.io",
            "intercom.com",
            "api.intercom.io",
            "widget.intercom.io",

            "zendesk.com",
            "zdassets.com",
            "zopim.com",

            "drift.com",
            "js.driftt.com",
            "event.drift.com",

            "freshworks.com",
            "freshchat.com",
            "wchat.freshchat.com",

            "helpscout.net",
            "beacon.helpscout.net",

            "crisp.chat",
            "client.crisp.chat",

            "tawk.to",
            "embed.tawk.to",

            "livechatinc.com",
            "cdn.livechatinc.com",

            "olark.com",
            "static.olark.com",

            "hubspot.com",
            "hubspot.net",
            "hs-scripts.com",
            "hs-banner.com",
            "hs-analytics.net",
            "hscollectedforms.net",
            "hsforms.net",
            "usemessages.com",
            "hscta.net",

            "marketo.com",
            "marketo.net",
            "munchkin.marketo.net",

            "pardot.com",
            "pi.pardot.com",
            "cdn.pardot.com",

            "eloqua.com",
            "en25.com",

            "salesforce.com",
            "exacttarget.com",

            "mailchimp.com",
            "chimpstatic.com",
            "list-manage.com",
            "mailchimp.io",

            "klaviyo.com",
            "static.klaviyo.com",
            "a.klaviyo.com",

            "sendgrid.com",
            "sendgrid.net",

            "constantcontact.com",

            "customer.io",
            "track.customer.io",

            "iterable.com",
            "links.iterable.com",

            // =====================================================
            // ERROR TRACKING / MONITORING
            // =====================================================
            "sentry.io",
            "browser.sentry-cdn.com",
            "ingest.sentry.io",

            "bugsnag.com",
            "notify.bugsnag.com",
            "sessions.bugsnag.com",

            "rollbar.com",
            "api.rollbar.com",

            "loggly.com",
            "logs-01.loggly.com",

            "raygun.com",
            "raygun.io",

            "newrelic.com",
            "js-agent.newrelic.com",
            "bam.nr-data.net",

            "datadog.com",
            "datadoghq.com",
            "browser-intake-datadoghq.com",

            "elastic.co",
            "rum.elastic.co",

            // =====================================================
            // AB TESTING / OPTIMIZATION
            // =====================================================
            "optimizely.com",
            "cdn.optimizely.com",
            "logx.optimizely.com",

            "abtasty.com",
            "try.abtasty.com",

            "vwo.com",
            "dev.visualwebsiteoptimizer.com",

            "kameleoon.com",
            "kameleoon.eu",

            "qubit.com",

            // =====================================================
            // AUDIENCE / DMP
            // =====================================================
            "bluekai.com",
            "tags.bluekai.com",

            "krxd.net",
            "cdn.krxd.net",
            "beacon.krxd.net",

            "demdex.net",
            "dpm.demdex.net",

            "omtrdc.net",
            "sc.omtrdc.net",
            "metrics.omtrdc.net",

            "2o7.net",

            "scorecardresearch.com",
            "sb.scorecardresearch.com",

            "quantserve.com",
            "pixel.quantserve.com",

            "comscore.com",
            "b.scorecardresearch.com",

            "Nielsen.com",
            "imrworldwide.com",
            "secure-us.imrworldwide.com",

            "gemius.pl",
            "gemius.com",

            // =====================================================
            // FINGERPRINTING / DEVICE ID
            // =====================================================
            "fingerprintjs.com",
            "fpjs.io",
            "api.fpjs.io",

            "iovation.com",

            "threatmetrix.com",
            "h.online-metrix.net",

            "deviceatlas.com",

            "51degrees.com",

            // =====================================================
            // SOCIAL / SHARE TRACKING
            // =====================================================
            "addthis.com",
            "m.addthis.com",
            "s7.addthis.com",
            "v1.addthis.com",

            "addtoany.com",
            "static.addtoany.com",

            "sharethis.com",
            "w.sharethis.com",
            "st-gdpr.sharethis.com",

            "sumo.com",
            "sumo-grow.com",

            "po.st",
            "w.po.st",

            // =====================================================
            // DIVERS TRACKING
            // =====================================================
            "flurry.com",
            "data.flurry.com",

            "localytics.com",
            "webcontentassessor.com",
            "kissmetrics.com",
            "statcounter.com",
            "histats.com",
            "hit.gemius.pl",
            "chartbeat.com",
            "chartbeat.net",
            "static.chartbeat.com",
            "parsely.com",
            "cdn.parsely.com",
            "d1z2jf7jlzjs58.cloudfront.net",
            "pixel.parsely.com",
            "getclicky.com",
            "in.getclicky.com",
            "static.getclicky.com",
            "woopra.com",
            "gosquared.com",
            "pingdom.net",
            "rum-static.pingdom.net",
            "speedcurve.com",

            // =====================================================
            // FRENCH TRACKERS
            // =====================================================
            "xiti.com",
            "ati-host.net",
            "at-internet.com",
            "atinternet.com",
            "wysistat.com",
            "estat.com",
            "eulerian.net",
            "eulerian.com",
            "mediarithmics.com",
            "mtrics.fr"
        ))
    }

    private fun loadMalwareDomains() {
        malwareDomains.addAll(listOf(
            // =====================================================
            // KNOWN MALWARE DOMAINS
            // =====================================================
            "malware.wicar.org",
            "malware-traffic-analysis.net",

            // =====================================================
            // PHISHING PATTERNS (exemples)
            // =====================================================
            "secure-login-verify.com",
            "account-verify-secure.com",
            "login-secure-verify.com",
            "update-account-secure.com",

            // =====================================================
            // CRYPTOMINERS
            // =====================================================
            "coinhive.com",
            "coin-hive.com",
            "authedmine.com",
            "crypto-loot.com",
            "cryptoloot.pro",
            "cryptonight.wasm",
            "jsecoin.com",
            "monerominer.rocks",
            "webmine.cz",
            "webminepool.com",
            "minero.cc",
            "coinhive-manager.com",
            "coinerra.com",
            "coinhiveproxy.com",
            "minerhills.com",
            "minemytraffic.com",
            "ppoi.org",
            "projectpoi.com",
            "coinblind.com",
            "coinnebula.com",
            "miner.pr0gramm.com",
            "hashvault.pro",
            "minergate.com",
            "de-miner.com",
            "bmnr.pw",
            "mineralt.io",
            "rocks.io",
            "greenindex.dynamic-dns.net",
            "2giga.link",
            "coinpirate.cf",
            "ad-miner.com",
            "webassembly.stream",
            "sparechange.io",
            "freecontent.bid",

            // =====================================================
            // SUSPICIOUS TLDS (exemples)
            // =====================================================
            "suspicious-site.xyz",
            "malware-download.tk",
            "phishing-page.ml"
        ))
    }

    fun loadCustomLists() {
        runBlocking {
            try {
                val domains = database.customListDao().getAllEnabledDomains()
                customDomains.clear()
                customDomains.addAll(domains)
            } catch (e: Exception) {
                // Ignorer si la table n'existe pas encore
            }
        }
    }

    fun loadWhitelist() {
        runBlocking {
            try {
                val items = database.whitelistDao().getAllSync()
                whitelistedDomains.clear()
                whitelistedDomains.addAll(items.map { it.domain })
            } catch (e: Exception) {
                // Ignorer
            }
        }
    }

    fun isWhitelisted(hostname: String): Boolean {
        val domain = hostname.lowercase()
        return whitelistedDomains.any {
            domain == it || domain.endsWith(".$it")
        }
    }

    fun isAd(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return adDomains.any { domain == it || domain.endsWith(".$it") } ||
                customDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun isTracker(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return trackerDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun isMalware(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return malwareDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun refresh() {
        loadCustomLists()
        loadWhitelist()
    }

    fun getStats(): Stats {
        return Stats(
            builtInAds = adDomains.size,
            builtInTrackers = trackerDomains.size,
            builtInMalware = malwareDomains.size,
            customDomains = customDomains.size,
            whitelisted = whitelistedDomains.size
        )
    }

    data class Stats(
        val builtInAds: Int,
        val builtInTrackers: Int,
        val builtInMalware: Int,
        val customDomains: Int,
        val whitelisted: Int
    )
}