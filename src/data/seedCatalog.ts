import {
  StudyBranch,
  StudyFileMetadata,
  StudyPageContent,
  StudySection,
  StudySubject,
} from '../domain/models';

export const SEED_BRANCHES: StudyBranch[] = [
  {
    id: 'scientific',
    name: 'الفرع العلمي',
    order: 0,
    isPublished: true,
    referencePath: 'branches/scientific',
  },
  {
    id: 'literary',
    name: 'الفرع الأدبي',
    order: 1,
    isPublished: true,
    referencePath: 'branches/literary',
  },
];

export const SEED_SUBJECTS: StudySubject[] = [
  // الفرع العلمي (7 مواد)
  {
    id: 'math_sci',
    branchId: 'scientific',
    name: 'الرياضيات',
    order: 0,
    isPublished: true,
    branchReferencePath: 'branches/scientific',
  },
  {
    id: 'islamic_sci',
    branchId: 'scientific',
    name: 'التربية الإسلامية',
    order: 1,
    isPublished: true,
    branchReferencePath: 'branches/scientific',
  },
  {
    id: 'arabic_sci',
    branchId: 'scientific',
    name: 'اللغة العربية',
    order: 2,
    isPublished: true,
    branchReferencePath: 'branches/scientific',
  },
  {
    id: 'english_sci',
    branchId: 'scientific',
    name: 'اللغة الإنكليزية',
    order: 3,
    isPublished: true,
    branchReferencePath: 'branches/scientific',
  },
  {
    id: 'chemistry_sci',
    branchId: 'scientific',
    name: 'الكيمياء',
    order: 4,
    isPublished: true,
    branchReferencePath: 'branches/scientific',
  },
  {
    id: 'physics_sci',
    branchId: 'scientific',
    name: 'الفيزياء',
    order: 5,
    isPublished: true,
    branchReferencePath: 'branches/scientific',
  },
  {
    id: 'biology_sci',
    branchId: 'scientific',
    name: 'الأحياء',
    order: 6,
    isPublished: true,
    branchReferencePath: 'branches/scientific',
  },

  // الفرع الأدبي (7 مواد)
  {
    id: 'islamic_lit',
    branchId: 'literary',
    name: 'التربية الإسلامية',
    order: 0,
    isPublished: true,
    branchReferencePath: 'branches/literary',
  },
  {
    id: 'arabic_lit',
    branchId: 'literary',
    name: 'اللغة العربية',
    order: 1,
    isPublished: true,
    branchReferencePath: 'branches/literary',
  },
  {
    id: 'english_lit',
    branchId: 'literary',
    name: 'اللغة الإنكليزية',
    order: 2,
    isPublished: true,
    branchReferencePath: 'branches/literary',
  },
  {
    id: 'math_lit',
    branchId: 'literary',
    name: 'الرياضيات',
    order: 3,
    isPublished: true,
    branchReferencePath: 'branches/literary',
  },
  {
    id: 'geography_lit',
    branchId: 'literary',
    name: 'الجغرافية',
    order: 4,
    isPublished: true,
    branchReferencePath: 'branches/literary',
  },
  {
    id: 'history_lit',
    branchId: 'literary',
    name: 'التاريخ',
    order: 5,
    isPublished: true,
    branchReferencePath: 'branches/literary',
  },
  {
    id: 'economics_lit',
    branchId: 'literary',
    name: 'الاقتصاد',
    order: 6,
    isPublished: true,
    branchReferencePath: 'branches/literary',
  },
];

export const SEED_SECTIONS: StudySection[] = [
  {
    id: 'math_calculus',
    subjectId: 'math_sci',
    name: 'التفاضل والتكامل',
    order: 0,
    isPublished: true,
    subjectReferencePath: 'subjects/math_sci',
  },
  {
    id: 'math_algebra',
    subjectId: 'math_sci',
    name: 'الجبر والهندسة التحليلية',
    order: 1,
    isPublished: true,
    subjectReferencePath: 'subjects/math_sci',
  },
  {
    id: 'physics_mechanics',
    subjectId: 'physics_sci',
    name: 'الميكانيكا والحركة',
    order: 0,
    isPublished: true,
    subjectReferencePath: 'subjects/physics_sci',
  },
  {
    id: 'chemistry_organic',
    subjectId: 'chemistry_sci',
    name: 'الكيمياء العضوية',
    order: 0,
    isPublished: true,
    subjectReferencePath: 'subjects/chemistry_sci',
  },
  {
    id: 'arabic_grammar',
    subjectId: 'arabic_lit',
    name: 'النحو والصرف والبلاغة',
    order: 0,
    isPublished: true,
    subjectReferencePath: 'subjects/arabic_lit',
  },
  {
    id: 'history_modern',
    subjectId: 'history_lit',
    name: 'تاريخ الحضارة الإسلامية',
    order: 0,
    isPublished: true,
    subjectReferencePath: 'subjects/history_lit',
  },
];

export const SEED_STUDY_FILES: StudyFileMetadata[] = [
  {
    id: 'calc_limits_guide',
    sectionId: 'math_calculus',
    sectionReferencePath: 'sections/math_calculus',
    title: 'ملخص النهايات والاتصال والمشتقات',
    description: 'شرح شامل لقواعد النهايات، الاتصال عند نقطة، وقواعد الاشتقاق مع أمثلة محلولة.',
    order: 0,
    isPublished: true,
    isArchived: false,
    mimeType: 'application/pdf',
    storageProvider: 'cloudflare_r2',
    activeVersionId: 'v1',
    activeVersionNumber: 1,
    activeVersionLabel: '1.0',
  },
  {
    id: 'calc_integrals_bank',
    sectionId: 'math_calculus',
    sectionReferencePath: 'sections/math_calculus',
    title: 'بنك أسئلة التكامل المحدود وتطبيقاته',
    description: 'تمارين متدرجة في التكامل بالتعويض والتجزئة وحساب المساحات.',
    order: 1,
    isPublished: true,
    isArchived: false,
    mimeType: 'application/pdf',
    storageProvider: 'cloudflare_r2',
    activeVersionId: 'v2',
    activeVersionNumber: 2,
    activeVersionLabel: '2.1',
  },
  {
    id: 'algebra_matrices',
    sectionId: 'math_algebra',
    sectionReferencePath: 'sections/math_algebra',
    title: 'المصفوفات والمحددات وحل الأنظمة الخطية',
    description: 'قواعد العمليات على المصفوفات وطريقة كرامر لحل المعادلات.',
    order: 0,
    isPublished: true,
    isArchived: false,
    mimeType: 'application/pdf',
    storageProvider: 'cloudflare_r2',
    activeVersionId: 'v1',
    activeVersionNumber: 1,
    activeVersionLabel: '1.0',
  },
  {
    id: 'physics_newton',
    sectionId: 'physics_mechanics',
    sectionReferencePath: 'sections/physics_mechanics',
    title: 'قوانين نيوتن في الحركة وتطبيقاتها',
    description: 'تحليل القوى على الأجسام المتحركة، الاحتكاك، والحركة الدائرية المنتظمة.',
    order: 0,
    isPublished: true,
    isArchived: false,
    mimeType: 'application/pdf',
    storageProvider: 'cloudflare_r2',
    activeVersionId: 'v1',
    activeVersionNumber: 1,
    activeVersionLabel: '1.2',
  },
  {
    id: 'chem_hydrocarbons',
    sectionId: 'chemistry_organic',
    sectionReferencePath: 'sections/chemistry_organic',
    title: 'مدخل إلى الهيدروكربونات والمجموعات الوظيفية',
    description: 'تسمية الألكانات والألكينات والألكاينات وتفاعلات الاستبدال والإضافة.',
    order: 0,
    isPublished: true,
    isArchived: false,
    mimeType: 'application/pdf',
    storageProvider: 'cloudflare_r2',
    activeVersionId: null,
    activeVersionNumber: null,
    activeVersionLabel: null,
  },
  {
    id: 'arabic_rhetoric',
    sectionId: 'arabic_grammar',
    sectionReferencePath: 'sections/arabic_grammar',
    title: 'دروس في البلاغة العربية: البيان والبديع',
    description: 'التشبيه والاستعارة والكناية والمحسنات البديعية مع شواهد شعرية.',
    order: 0,
    isPublished: true,
    isArchived: false,
    mimeType: 'application/pdf',
    storageProvider: 'cloudflare_r2',
    activeVersionId: 'v1',
    activeVersionNumber: 1,
    activeVersionLabel: '1.0',
  },
  {
    id: 'history_andalus',
    sectionId: 'history_modern',
    sectionReferencePath: 'sections/history_modern',
    title: 'الإسهامات العلمية والثقافية في الأندلس',
    description: 'استعراض أبرز المراكز العلمية في قرطبة وطليطلة وأثرها في النهضة العلمية.',
    order: 0,
    isPublished: true,
    isArchived: false,
    mimeType: 'application/pdf',
    storageProvider: 'cloudflare_r2',
    activeVersionId: 'v1',
    activeVersionNumber: 1,
    activeVersionLabel: '1.0',
  },
];

export const SEED_DOCUMENT_PAGES: Record<string, StudyPageContent[]> = {
  calc_limits_guide: [
    {
      pageNumber: 1,
      heading: 'الفصل الأول: مفهوم النهاية وقوانينها الأساسية',
      paragraphs: [
        'تُعد النهاية حجر الأساس في حساب التفاضل والتكامل، فهي تصف سلوك الدالة f(x) عندما يقترب المتغير المستقل x من قيمة محددة c دون أن يساويها بالضرورة.',
        'إذا كانت نهاية الدالة من اليمين تساوي نهايتها من اليسار وتساوي قيمة عددية حقيقية L، فإن النهاية موجودة وتساوي L. أما إذا اختلفت النهاية اليمنى عن اليسرى فالنهاية غير موجودة عند تلك النقطة.',
        'من أهم خصائص النهايات: نهاية مجموع دالتين تساوي مجموع نهايتيهما، ونهاية حاصل الضرب تساوي حاصل ضرب النهايتين بشرط وجود كل منهما.',
      ],
      keyTakeaway: 'تذكر دائماً فحص النهاية اليمنى واليسرى في الدوال المتشعبة ودوال القيمة المطلقة.',
    },
    {
      pageNumber: 2,
      heading: 'ثانياً: حالات عدم التعيين وطرق معالجتها',
      paragraphs: [
        'عند التعويض المباشر في النهاية قد نحصل على صيغة غير معيّنة مثل 0/0 أو ∞/∞. في هذه الحالة لا يعني ذلك عدم وجود النهاية، بل يتطلب تبسيط المقدار جبرياً.',
        'أبرز طرق إزالة عدم التعيين: التحليل إلى العوامل (الفرق بين مربعين، الفرق بين مكعبين)، الضرب في المرافق للجذور التربيعية، وتوحيد المقامات في الكسور المركبة.',
        'مثال تطبيقي: لإيجاد نهاية (x² - 9) / (x - 3) عندما x تؤول إلى 3، نحلل البسط إلى (x - 3)(x + 3) ثم نختصر العامل الصفري لنحصل على القيمة 6.',
      ],
      keyTakeaway: 'التحليل والاختصار يزيلان العامل الصفري الذي يسبب صيغة 0/0.',
    },
    {
      pageNumber: 3,
      heading: 'ثالثاً: الاتصال عند نقطة وعلى فترة',
      paragraphs: [
        'تكون الدالة f متصلة عند النقطة x = c إذا تحققت ثلاثة شروط مجتمعة: أن تكون f(c) معرّفة، وأن تكون نهاية f(x) عند c موجودة، وأن تتساوى قيمة النهاية مع قيمة الدالة f(c).',
        'الدوال كثيرات الحدود متصلة دائماً على مجموعة الأعداد الحقيقية R، بينما الدوال النسبية متصلة على مجالها باستثناء أصفار المقام.',
      ],
      keyTakeaway: 'الاتصال يعني عدم وجود انقطاع أو قفزة أو فجوة في منحنى الدالة.',
    },
    {
      pageNumber: 4,
      heading: 'رابعاً: المشتقة الأولى وقواعد الاشتقاق',
      paragraphs: [
        'المشتقة الأولى للدالة عند نقطة تمثل ميل المماس لمنحنى الدالة عند تلك النقطة، كما تعبّر فيزيائياً عن معدل التغير اللحظي.',
        'قواعد الاشتقاق الأساسية تشمل: مشتقة الثابت تساوي صفراً، مشتقة xⁿ تساوي n·xⁿ⁻¹، وقاعدة السلسلة لاشتقاق الدوال المركبة.',
        'تُستخدم إشارة المشتقة الأولى لتحديد فترات التزايد والتناقص، وإيجاد القيم العظمى والصغرى المحلية للدالة.',
      ],
      keyTakeaway: 'كل دالة قابلة للاشتقاق عند نقطة تكون متصلة عندها، وليس العكس بالضرورة.',
    },
  ],
  calc_integrals_bank: [
    {
      pageNumber: 1,
      heading: 'التكامل غير المحدود والدالة الأصلية',
      paragraphs: [
        'يمثل التكامل العملية العكسية للاشتقاق؛ فإذا كانت F\'(x) = f(x) فإن F(x) + C هي الدالة الأصلية العامة للدالة f(x)، حيث C ثابت التكامل.',
        'لإيجاد تكامل xⁿ بالنسبة إلى x (حيث n ≠ -1) نضيف واحداً إلى الأس ونقسم على الأس الجديد: ∫xⁿ dx = xⁿ⁺¹ / (n + 1) + C.',
      ],
      keyTakeaway: 'لا تنسَ إضافة ثابت التكامل C في جميع مسائل التكامل غير المحدود.',
    },
    {
      pageNumber: 2,
      heading: 'التكامل بالتعويض والتكامل بالتجزئة',
      paragraphs: [
        'يُستخدم التكامل بالتعويض عندما يحتوي التكامل على دالة مركبة ومضروباً في مشتقة داخلها، حيث نفرض u = g(x) ونحوّل المتغير كاملاً إلى u.',
        'أما التكامل بالتجزئة فيعتمد على قاعدة: ∫u dv = u·v - ∫v du، وهو مثالي لحاصل ضرب دالة كثيرة حدود في دالة أسية أو مثلثية.',
      ],
      keyTakeaway: 'اختيار u الصحيح في التكامل بالتجزئة يبسّط المسألة في خطوة واحدة.',
    },
    {
      pageNumber: 3,
      heading: 'التكامل المحدود وحساب المساحات',
      paragraphs: [
        'وفق النظرية الأساسية للتفاضل والتكامل، فإن ∫ₐᵇ f(x) dx = F(b) - F(a)، وهو يعبر هندسياً عن المساحة الجبرية المحصورة بين المنحنى ومحور السينات.',
        'عند حساب المساحة الهندسية الفعلية يجب تقسيم الفترة عند جذور الدالة وأخذ القيمة المطلقة للمساحات الواقعة أسفل المحور.',
      ],
    },
  ],
  algebra_matrices: [
    {
      pageNumber: 1,
      heading: 'تعريف المصفوفات والعمليات الجبرية',
      paragraphs: [
        'المصفوفة هي تنظيم مستطيل من الأعداد مرتبة في صفوف وأعمدة. تُحدَّد رتبة المصفوفة بعدد صفوفها m في عدد أعمدتها n وتُكتب m × n.',
        'يشترط لجمع أو طرح مصفوفتين أن تكونا من الرتبة نفسها، بينما يشترط لضرب مصفوفتين A × B أن يساوي عدد أعمدة A عدد صفوف B.',
      ],
      keyTakeaway: 'عملية ضرب المصفوفات ليست إبدالية في الحالة العامة.',
    },
    {
      pageNumber: 2,
      heading: 'المحددات وطريقة كرامر',
      paragraphs: [
        'المحدد عدد حقيقي يرتبط بالمصفوفة المربعة فقط. لمصفوفة 2×2 يُحسب المحدد بطرح حاصل ضرب القطر الثانوي من حاصل ضرب القطر الرئيسي.',
        'تتيح طريقة كرامر حل نظام من المعادلات الخطية باستخدام نسب المحددات بشرط ألا يساوي محدد مصفوفة المعاملات صفراً.',
      ],
    },
  ],
  physics_newton: [
    {
      pageNumber: 1,
      heading: 'قانون نيوتن الأول والثاني في الحركة',
      paragraphs: [
        'ينص قانون نيوتن الأول (القصور الذاتي) على أن الجسم الساكن يبقى ساكناً والجسم المتحرك بسرعة ثابتة في خط مستقيم يبقى كذلك ما لم تؤثر عليه قوة محصلة غير متزنة.',
        'يربط قانون نيوتن الثاني بين القوة المحصلة F والكتلة m والتسارع a بالعلاقة الشهيرة: ΣF = m · a، حيث يكون اتجاه التسارع دائماً في اتجاه القوة المحصلة.',
      ],
      keyTakeaway: 'ابدأ كل مسألة ميكانيكا برسم مخطط الجسم الحر وتحديد جميع القوى المؤثرة.',
    },
    {
      pageNumber: 2,
      heading: 'قانون نيوتن الثالث وقوى الاحتكاك',
      paragraphs: [
        'لكل فعل رد فعل مساوٍ له في المقدار ومعاكس له في الاتجاه، وتؤثر القوتان في جسمين مختلفين لذلك لا تلغي إحداهما الأخرى.',
        'تنقسم قوة الاحتكاك إلى احتكاك سكوني يمنع بدء الحركة واحتكاك حركي يقاوم استمرارها، ويتناسب كلاهما طردياً مع القوة العمودية N.',
      ],
    },
  ],
  arabic_rhetoric: [
    {
      pageNumber: 1,
      heading: 'علم البيان: التشبيه وأركانه',
      paragraphs: [
        'التشبيه هو عقد مقارنة بين شيئين اشتركا في صفة أو أكثر، وأركانه الأربعة هي: المشبه، المشبه به، أداة التشبيه، ووجه الشبه.',
        'إذا حُذفت أداة التشبيه ووجه الشبه سُمي التشبيه بليغاً، وهو أرفع درجات التشبيه وأقواها تأثيراً في النفس.',
      ],
      keyTakeaway: 'الطرفان الأساسيان في التشبيه هما المشبه والمشبه به ولا يصح حذف أحدهما وإلا صار استعارة.',
    },
    {
      pageNumber: 2,
      heading: 'الاستعارة والكناية والمحسنات البديعية',
      paragraphs: [
        'الاستعارة تشبيه بليغ حُذف أحد طرفيه؛ فإن حُذف المشبه به ورُمز له بشيء من لوازمه فهي استعارة مكنية، وإن صُرّح بالمشبه به فهي استعارة تصريحية.',
        'من المحسنات المعنوية الطباق والمقابلة، ومن المحسنات اللفظية الجناس والسجع اللذان يضفيان إيقاعاً موسيقياً على النص الأدبي.',
      ],
    },
  ],
  history_andalus: [
    {
      pageNumber: 1,
      heading: 'قرطبة وطليطلة: منارات العلم في الأندلس',
      paragraphs: [
        'شهدت الأندلس ازدهاراً علمياً وثقافياً فريداً، حيث أصبحت قرطبة مركزاً عالمياً للعلم والمكتبات، وضمت جامعاتها ومجالسها العلمية علماء في الطب والفلك والرياضيات والفلسفة.',
        'في طليطلة نشطت حركة الترجمة من العربية إلى اللاتينية، مما نقل التراث العلمي العربي والإغريقي المترجم إلى أوروبا وأسهم في نهضتها العلمية.',
      ],
      keyTakeaway: 'تنوعت العلوم الأندلسية بين الطب والجراحة (الزهراوي) والفلسفة (ابن رشد) والجغرافيا (الإدريسي).',
    },
    {
      pageNumber: 2,
      heading: 'العمارة والآثار الحضارية الباقية',
      paragraphs: [
        'تجلت العبقريّة الهندسية والفنية في معالم خالدة مثل جامع قرطبة الكبير وقصر الحمراء في غرناطة، بما تضمنته من هندسة مائية وزخارف نباتية وهندسية دقيقة.',
      ],
    },
  ],
};

export function getDocumentPages(file: StudyFileMetadata): StudyPageContent[] {
  if (SEED_DOCUMENT_PAGES[file.id]) {
    return SEED_DOCUMENT_PAGES[file.id];
  }
  return [
    {
      pageNumber: 1,
      heading: file.title,
      paragraphs: [
        file.description || 'مادة دراسية مضافة عبر لوحة إدارة المحتوى في حقيبتي.',
        'يُعرض هذا المستند داخل قارئ حقيبتي المدمج مع دعم كامل للغة العربية، البحث النصي المطبّع، التكبير، الوضع الليلي، وحفظ آخر صفحة مقروءة محلياً على هذا الجهاز.',
      ],
      keyTakeaway: 'يمكنك الانتقال بين الصفحات والبحث داخل النص العربي بحرية.',
    },
    {
      pageNumber: 2,
      heading: 'ملخص وملاحظات الدراسة',
      paragraphs: [
        'تتيح لك منصة حقيبتي استعراض الوحدات الدراسية وتنظيم ملفاتك التعليمية بوضوح، مع الاحتفاظ بتقدم القراءة محلياً بشكل مستقل عن الحساب.',
      ],
    },
  ];
}
