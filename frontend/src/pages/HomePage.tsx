import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import {
  Inbox,
  BotMessageSquare,
  Zap,
  ArrowRightLeft,
  Tags,
  ShieldCheck,
  Check,
  Clock,
  Sparkles,
} from 'lucide-react';
import styles from './HomePage.module.css';

const FEATURES = [
  {
    icon: Inbox,
    title: 'One unified inbox',
    desc: 'WhatsApp, SMS, email, and web chat in a single stream. No more switching tabs.',
  },
  {
    icon: BotMessageSquare,
    title: 'AI that drafts, you approve',
    desc: 'GPT-powered replies drafted in seconds. Review, tweak, send - or let it fly automatically.',
    highlight: true,
  },
  {
    icon: Zap,
    title: 'Speed-to-lead automatically',
    desc: 'New leads get an instant reply even when you are on a job. Never lose a customer to silence.',
  },
  {
    icon: ArrowRightLeft,
    title: 'Smart handoff on quotes',
    desc: 'AI handles FAQs and scheduling. The moment pricing comes up, it tags you in.',
  },
  {
    icon: Tags,
    title: 'Organize your way',
    desc: 'Labels, filters, and smart views so your team can triage without stepping on toes.',
  },
  {
    icon: ShieldCheck,
    title: 'Your data stays yours',
    desc: 'SOC 2-ready infrastructure. Messages encrypted in transit and at rest.',
  },
];

const STEPS = [
  {
    title: 'Connect your channels',
    desc: 'Link WhatsApp, SMS, and email in a few clicks. No developer needed.',
  },
  {
    title: 'Let AI learn your voice',
    desc: 'Paste a few past replies - the AI matches your tone and common answers.',
  },
  {
    title: 'Start closing faster',
    desc: 'Every new message lands in one inbox with a draft reply ready to go.',
  },
] as const;

const PRICING = [
  {
    plan: 'Starter',
    price: '$0',
    period: '/mo',
    features: ['1 channel', '50 conversations/mo', 'AI draft suggestions', 'Email support'],
    popular: false,
  },
  {
    plan: 'Growth',
    price: '$39',
    period: '/mo',
    features: [
      'Unlimited channels',
      '1,000 conversations/mo',
      'AI auto-reply',
      'Speed-to-lead automation',
      'Priority support',
    ],
    popular: true,
  },
  {
    plan: 'Team',
    price: '$99',
    period: '/mo',
    features: [
      'Everything in Growth',
      '5 team seats',
      'Smart handoff rules',
      'Custom labels & views',
      'Dedicated account manager',
    ],
    popular: false,
  },
] as const;

interface InboxMessage {
  id: number;
  name: string;
  preview: string;
  channel: 'whatsapp' | 'sms' | 'email';
  tag?: 'hot' | 'new' | 'warn';
  gradient: string;
  entering?: boolean;
}

const AVATAR_GRADIENTS = [
  'linear-gradient(135deg, #818CF8, #4F46E5)',
  'linear-gradient(135deg, #F472B6, #EC4899)',
  'linear-gradient(135deg, #34D399, #10B981)',
  'linear-gradient(135deg, #FBBF24, #F59E0B)',
  'linear-gradient(135deg, #60A5FA, #3B82F6)',
  'linear-gradient(135deg, #A78BFA, #7C3AED)',
];

const MESSAGE_POOL: Omit<InboxMessage, 'id' | 'entering'>[] = [
  { name: 'Maria Santos', preview: 'Hi, do you service downtown?', channel: 'whatsapp', tag: 'hot', gradient: AVATAR_GRADIENTS[0] },
  { name: 'Jake Miller', preview: 'Can I get a quote for Sunday?', channel: 'sms', tag: 'new', gradient: AVATAR_GRADIENTS[1] },
  { name: 'Priya Patel', preview: 'Thanks for the quick reply!', channel: 'email', gradient: AVATAR_GRADIENTS[2] },
  { name: 'Tom Chen', preview: 'Is the 2pm slot still open?', channel: 'whatsapp', tag: 'new', gradient: AVATAR_GRADIENTS[3] },
  { name: 'Lisa Nguyen', preview: 'I need an emergency repair ASAP', channel: 'sms', tag: 'hot', gradient: AVATAR_GRADIENTS[4] },
  { name: 'Carlos Ruiz', preview: 'Following up on my inquiry', channel: 'email', tag: 'warn', gradient: AVATAR_GRADIENTS[5] },
  { name: 'Emma Wilson', preview: 'Do you offer financing?', channel: 'whatsapp', gradient: AVATAR_GRADIENTS[0] },
  { name: 'David Kim', preview: 'Great work on the install!', channel: 'sms', gradient: AVATAR_GRADIENTS[1] },
];

const CHANNEL_STYLES: Record<string, { className: string; label: string }> = {
  whatsapp: { className: styles.channelWhatsapp, label: 'WA' },
  sms: { className: styles.channelSms, label: 'SMS' },
  email: { className: styles.channelEmail, label: 'Email' },
};

const TAG_STYLES: Record<string, string> = {
  hot: styles.tagHot,
  new: styles.tagNew,
  warn: styles.tagWarn,
};

const TAG_LABELS: Record<string, string> = {
  hot: 'Hot',
  new: 'New',
  warn: 'Follow up',
};

const TRUST_COLORS = ['#818CF8', '#F472B6', '#34D399', '#FBBF24', '#60A5FA'];

let nextId = 100;

export function HomePage() {
  const [scrolled, setScrolled] = useState(false);
  const [messages, setMessages] = useState<InboxMessage[]>(() =>
    MESSAGE_POOL.slice(0, 3).map((m, i) => ({ ...m, id: i }))
  );
  const poolIndex = useRef(3);

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 10);
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (prefersReduced) return;

    const interval = setInterval(() => {
      const template = MESSAGE_POOL[poolIndex.current % MESSAGE_POOL.length];
      poolIndex.current++;
      const newMsg: InboxMessage = { ...template, id: nextId++, entering: true };

      setMessages((prev) => [newMsg, ...prev.slice(0, 4)]);

      setTimeout(() => {
        setMessages((prev) =>
          prev.map((m) => (m.id === newMsg.id ? { ...m, entering: false } : m))
        );
      }, 600);
    }, 4000);

    return () => clearInterval(interval);
  }, []);

  useRevealOnScroll();

  return (
    <div>
      <Nav scrolled={scrolled} />
      <HeroSection messages={messages} />
      <LogoStrip />
      <FeaturesSection />
      <HowItWorks />
      <PricingSection />
      <CtaBand />
      <Footer />
    </div>
  );
}

function useRevealOnScroll() {
  useEffect(() => {
    const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (prefersReduced) return;

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add(styles.revealVisible);
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12 }
    );

    const els = document.querySelectorAll(`.${styles.reveal}`);
    els.forEach((el) => observer.observe(el));

    return () => observer.disconnect();
  }, []);
}

function Nav({ scrolled }: { scrolled: boolean }) {
  const navClass = `${styles.nav} ${scrolled ? styles.navScrolled : ''}`;

  return (
    <nav className={navClass}>
      <div className={`${styles.wrap} ${styles.navInner}`}>
        <Link to="/" className={styles.logoGroup}>
          <div className={styles.logoMark}>
            <span className={styles.logoMarkDot} />
            <span className={styles.logoPulse} />
          </div>
          <span className={styles.logoText}>Unified Messaging</span>
        </Link>

        <ul className={styles.navLinks}>
          <li><a href="#features" className={styles.navLink}>Features</a></li>
          <li><a href="#how" className={styles.navLink}>How it works</a></li>
          <li><a href="#pricing" className={styles.navLink}>Pricing</a></li>
          <li><Link to="/login" className={styles.navLink}>Live demo</Link></li>
        </ul>

        <div className={styles.navActions}>
          <Link to="/login" className={styles.btnGhost}>Log in</Link>
          <Link to="/login" className={styles.btnPrimary}>Start free</Link>
        </div>
      </div>
    </nav>
  );
}

function HeroSection({ messages }: { messages: InboxMessage[] }) {
  return (
    <section className={styles.hero}>
      <div className={styles.wrap}>
        <div className={styles.heroGrid}>
          <div className={styles.heroLeft}>
            <span className={styles.eyebrow}>
              <span className={styles.eyebrowDot} />
              WhatsApp &middot; SMS &middot; Email - one inbox
            </span>

            <h1 className={styles.heroTitle}>
              Never miss a{' '}
              <span className={styles.heroTitleAccent}>lead</span>{' '}
              again
            </h1>

            <p className={styles.heroLead}>
              Every WhatsApp, text, and email lands in one inbox with an AI-drafted
              reply ready before you put down your tools. Built for the trades.
            </p>

            <div className={styles.heroCtas}>
              <Link to="/login" className={`${styles.btnHero} ${styles.btnHeroIndigo}`}>
                Start free
              </Link>
              <Link to="/login" className={`${styles.btnHero} ${styles.btnHeroWhite}`}>
                See the inbox demo
              </Link>
            </div>

            <div className={styles.trustRow}>
              <div className={styles.trustAvatars}>
                {TRUST_COLORS.map((color, i) => (
                  <div
                    key={i}
                    className={styles.trustAvatar}
                    style={{ background: color }}
                  />
                ))}
              </div>
              <span className={styles.trustText}>
                Trusted by 1,200+ plumbers, realtors &amp; studios
              </span>
            </div>
          </div>

          <div className={styles.heroRight}>
            <DeviceMockup messages={messages} />

            <div className={`${styles.floatCard} ${styles.floatReply}`}>
              <div className={`${styles.floatIcon} ${styles.floatIconGreen}`}>
                <Clock size={16} />
              </div>
              Replied in 12 seconds
            </div>

            <div className={`${styles.floatCard} ${styles.floatAi}`}>
              <div className={`${styles.floatIcon} ${styles.floatIconPurple}`}>
                <Sparkles size={16} />
              </div>
              AI drafted this reply
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function DeviceMockup({ messages }: { messages: InboxMessage[] }) {
  return (
    <div className={styles.device}>
      <div className={styles.deviceBar}>
        <span className={`${styles.deviceDot} ${styles.deviceDotRed}`} />
        <span className={`${styles.deviceDot} ${styles.deviceDotYellow}`} />
        <span className={`${styles.deviceDot} ${styles.deviceDotGreen}`} />
      </div>

      <div className={styles.deviceHeader}>
        <h3 className={styles.deviceTitle}>Inbox</h3>
        <span className={styles.livePill}>
          <span className={styles.livePillDot} />
          Live
        </span>
      </div>

      <div className={styles.deviceMessages}>
        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`${styles.msgRow} ${msg.entering ? styles.msgEnter : ''}`}
          >
            <div
              className={styles.msgAvatar}
              style={{ background: msg.gradient }}
            />
            <div className={styles.msgBody}>
              <div className={styles.msgName}>{msg.name}</div>
              <div className={styles.msgPreview}>{msg.preview}</div>
            </div>
            <div className={styles.msgMeta}>
              <span className={`${styles.channelBadge} ${CHANNEL_STYLES[msg.channel].className}`}>
                {CHANNEL_STYLES[msg.channel].label}
              </span>
              {msg.tag && (
                <span className={`${styles.tag} ${TAG_STYLES[msg.tag]}`}>
                  {TAG_LABELS[msg.tag]}
                </span>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function LogoStrip() {
  const logos = ['PlumbRight', 'Casa Realty', 'Focal Studio', 'SwiftHVAC', 'Bloom & Co'];

  return (
    <section className={`${styles.logoStrip} ${styles.reveal}`}>
      <div className={styles.wrap}>
        <p className={styles.logoStripLabel}>
          Built for the businesses that live in their inbox
        </p>
        <div className={styles.logoStripGrid}>
          {logos.map((name) => (
            <span key={name} className={styles.logoStripItem}>{name}</span>
          ))}
        </div>
      </div>
    </section>
  );
}

function FeaturesSection() {
  return (
    <section id="features" className={`${styles.section} ${styles.reveal}`}>
      <div className={styles.wrap}>
        <div className={styles.sectionHeader}>
          <p className={styles.sectionEyebrow}>Everything in one place</p>
          <h2 className={styles.sectionTitle}>
            Stop losing customers to a missed message
          </h2>
          <p className={styles.sectionDesc}>
            One inbox, one AI assistant, one place to see every conversation
            across every channel your customers use.
          </p>
        </div>

        <div className={styles.featuresGrid}>
          {FEATURES.map(({ icon: Icon, title, desc, highlight }) => (
            <div key={title} className={styles.featureCard}>
              <div
                className={`${styles.featureIcon} ${highlight ? styles.featureIconHighlight : ''}`}
              >
                <Icon size={22} />
              </div>
              <h3 className={styles.featureTitle}>{title}</h3>
              <p className={styles.featureDesc}>{desc}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function HowItWorks() {
  return (
    <section id="how" className={`${styles.section} ${styles.reveal}`}>
      <div className={styles.wrap}>
        <div className={styles.howGrid}>
          <div className={styles.howLeft}>
            <div className={styles.sectionHeader} style={{ textAlign: 'left', margin: 0 }}>
              <p className={styles.sectionEyebrow}>Three steps</p>
              <h2 className={styles.sectionTitle}>
                Live in five minutes, not five days
              </h2>
            </div>

            {STEPS.map(({ title, desc }, i) => (
              <div key={title} className={styles.step}>
                <div className={styles.stepNum}>{i + 1}</div>
                <div className={styles.stepContent}>
                  <h3 className={styles.stepTitle}>{title}</h3>
                  <p className={styles.stepDesc}>{desc}</p>
                </div>
              </div>
            ))}
          </div>

          <div className={styles.howVisual}>
            <div className={`${styles.chatBubble} ${styles.chatIncoming}`}>
              Hi, I saw your ad on Google. Do you do same-day drain repairs?
            </div>
            <div className={styles.chatAiBadge}>
              <Sparkles size={12} />
              AI draft
            </div>
            <div className={`${styles.chatBubble} ${styles.chatOutgoing}`}>
              Hey! Yes we do - we have a slot open at 2pm today.
              Want me to lock it in? We charge $89 for the callout.
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function PricingSection() {
  return (
    <section id="pricing" className={`${styles.pricingSection} ${styles.reveal}`}>
      <div className={styles.wrap}>
        <div className={styles.sectionHeader}>
          <p className={styles.sectionEyebrow}>Simple pricing</p>
          <h2 className={styles.sectionTitle}>Start free, grow when ready</h2>
          <p className={styles.sectionDesc}>
            No credit card required. Upgrade or cancel any time.
          </p>
        </div>

        <div className={styles.pricingGrid}>
          {PRICING.map(({ plan, price, period, features, popular }) => (
            <div
              key={plan}
              className={`${styles.pricingCard} ${popular ? styles.pricingCardPopular : ''}`}
            >
              {popular && <span className={styles.popularBadge}>Most popular</span>}
              <h3 className={styles.pricingPlan}>{plan}</h3>
              <div className={styles.pricingPrice}>
                <span className={styles.priceAmount}>{price}</span>
                <span className={styles.pricePeriod}>{period}</span>
              </div>
              <ul className={styles.pricingFeatures}>
                {features.map((f) => (
                  <li key={f} className={styles.pricingFeature}>
                    <span className={styles.checkCircle}>
                      <Check size={12} strokeWidth={3} />
                    </span>
                    {f}
                  </li>
                ))}
              </ul>
              <div className={styles.pricingCta}>
                <Link
                  to="/login"
                  className={popular ? styles.btnPricingPrimary : styles.btnOutline}
                >
                  {popular ? 'Start free trial' : 'Get started'}
                </Link>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function CtaBand() {
  return (
    <section className={`${styles.ctaBand} ${styles.reveal}`}>
      <div className={styles.wrap}>
        <div className={styles.ctaBandInner}>
          <div className={`${styles.ctaCircle} ${styles.ctaCircle1}`} />
          <div className={`${styles.ctaCircle} ${styles.ctaCircle2}`} />
          <div className={`${styles.ctaCircle} ${styles.ctaCircle3}`} />
          <h2 className={styles.ctaBandTitle}>Your next lead is texting right now.</h2>
          <Link to="/login" className={styles.btnCtaWhite}>Start free</Link>
        </div>
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={styles.wrap}>
        <div className={styles.footerGrid}>
          <div className={styles.footerBrand}>
            <Link to="/" className={styles.logoGroup}>
              <div className={styles.logoMark}>
                <span className={styles.logoMarkDot} />
                <span className={styles.logoPulse} />
              </div>
              <span className={styles.logoText}>Unified Messaging</span>
            </Link>
            <p className={styles.footerBrandDesc}>
              One inbox for every customer message.
              AI-drafted replies so you never miss a lead.
            </p>
          </div>

          <div>
            <h4 className={styles.footerColTitle}>Product</h4>
            <ul className={styles.footerLinks}>
              <li><a href="#features" className={styles.footerLink}>Features</a></li>
              <li><a href="#pricing" className={styles.footerLink}>Pricing</a></li>
              <li><a href="#how" className={styles.footerLink}>How it works</a></li>
              <li><Link to="/login" className={styles.footerLink}>Live demo</Link></li>
            </ul>
          </div>

          <div>
            <h4 className={styles.footerColTitle}>Company</h4>
            <ul className={styles.footerLinks}>
              <li><a href="#" className={styles.footerLink}>About</a></li>
              <li><a href="#" className={styles.footerLink}>Blog</a></li>
              <li><a href="#" className={styles.footerLink}>Careers</a></li>
            </ul>
          </div>

          <div>
            <h4 className={styles.footerColTitle}>Support</h4>
            <ul className={styles.footerLinks}>
              <li><a href="#" className={styles.footerLink}>Help center</a></li>
              <li><a href="#" className={styles.footerLink}>Contact</a></li>
              <li><a href="#" className={styles.footerLink}>Privacy</a></li>
              <li><a href="#" className={styles.footerLink}>Terms</a></li>
            </ul>
          </div>
        </div>

        <div className={styles.footerBottom}>
          <p className={styles.footerCopy}>&copy; 2026 Unified Messaging</p>
          <p className={styles.footerTagline}>Built for businesses that answer fast</p>
        </div>
      </div>
    </footer>
  );
}
