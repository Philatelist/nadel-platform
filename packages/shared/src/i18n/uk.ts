import type { Messages } from './en';

// Ukrainian UI translation (default locale for end users)
export const uk: Messages = {
  app: { name: 'Наділ' },
  auth: {
    signIn: 'Увійти',
    signOut: 'Вийти',
    signingIn: 'Входимо…',
    signInHint: 'Увійдіть, щоб побачити свої ділянки та нарахування.',
  },
  nav: {
    account: 'Мій кабінет',
    members: 'Члени кооперативу',
    roles: 'Ролі',
    audit: 'Журнал змін',
    language: 'Мова',
  },
  state: {
    loading: 'Завантаження…',
    empty: 'Тут поки нічого немає.',
    error: 'Не вдалося завантажити дані. Спробуйте ще раз.',
  },
  access: {
    denied: 'У вас немає доступу до цієї сторінки',
    notFound: 'Не знайдено',
    notLinkedTitle: 'Обліковий запис не прив’язано',
    noCooperativeTitle: 'Немає кооперативу',
    noCooperative:
      'Ви оператор платформи й не належите до жодного кооперативу, тому даних кооперативу тут немає.',
  },
  account: {
    title: 'Мій кабінет',
    cooperative: 'Кооператив',
    roles: 'Ролі',
    permissions: 'Що вам дозволено',
    noPermissions: 'Ваші ролі не дають додаткових дозволів. Ви бачите лише свої дані.',
    details: 'Мої дані',
  },
  member: {
    fields: {
      name: 'ПІБ',
      phones: 'Телефони',
      email: 'Електронна пошта',
      postalAddress: 'Поштова адреса',
      deliveryChannel: 'Бажаний канал',
      telegramUsername: 'Нікнейм у Telegram',
      viberPhone: 'Телефон у Viber',
      notes: 'Примітки',
      roles: 'Ролі',
      none: '—',
    },
  },
  channel: { EMAIL: 'Електронна пошта', TELEGRAM: 'Telegram', VIBER: 'Viber', PAPER: 'Папір' },
  permission: {
    MEMBERS_VIEW: {
      name: 'Перегляд членів',
      description: 'Бачити всіх членів кооперативу, їхні контакти та примітки',
    },
    MEMBERS_EDIT: {
      name: 'Редагування членів',
      description: 'Додавати, редагувати, архівувати й відновлювати членів',
    },
    ROLES_MANAGE: {
      name: 'Керування ролями',
      description:
        'Створювати, змінювати й видаляти власні ролі, змінювати дозволи базових ролей, призначати ролі членам',
    },
    AUDIT_VIEW: { name: 'Перегляд журналу змін', description: 'Читати журнал змін' },
    PLOTS_VIEW: { name: 'Перегляд ділянок', description: 'Бачити всі ділянки з подробицями' },
    PLOTS_EDIT: { name: 'Редагування ділянок', description: 'Додавати, редагувати й архівувати ділянки' },
    OWNERSHIP_EDIT: {
      name: 'Редагування власності',
      description: 'Додавати, змінювати й виправляти власників ділянок',
    },
    IMPORT_RUN: { name: 'Запуск імпорту', description: 'Запускати імпорт з Excel' },
  },
  roles: {
    CHAIRMAN: 'Голова',
    TREASURER: 'Скарбник',
    MEMBER: 'Член кооперативу',
    PLATFORM_OPERATOR: 'Оператор платформи',
  },
  problem: {
    VALIDATION_FAILED: 'Деякі поля заповнено неправильно. Перевірте їх і спробуйте ще раз.',
    MALFORMED_REQUEST: 'Не вдалося розібрати запит.',
    UNAUTHENTICATED: 'Сеанс завершився. Увійдіть ще раз.',
    MEMBER_NOT_LINKED:
      'Ваш обліковий запис не прив’язано до члена цього кооперативу. Зверніться до голови кооперативу.',
    PERMISSION_DENIED: 'У вас немає дозволу на цю дію.',
    NOT_FOUND: 'Не знайдено',
    EMAIL_TAKEN: 'Цю електронну адресу вже використовує інший активний член кооперативу.',
    ROLE_NAME_TAKEN: 'Роль з такою назвою вже існує.',
    ROLE_IN_USE: 'Цю роль призначено членам кооперативу, тому її не можна видалити.',
    LAST_CHAIRMAN: 'У кооперативі має лишатися принаймні один голова.',
    BASE_ROLE_IMMUTABLE: 'Базові ролі не можна перейменовувати чи видаляти.',
    UNKNOWN: 'Щось пішло не так. Спробуйте ще раз.',
  },
  map: {
    title: 'Карта кооперативу',
    plot: 'Ділянка',
    area: 'Площа',
    sotky: 'сот.',
    debt: 'Борг',
    commonLand: 'Спільні землі',
    empty: 'Ділянок ще немає. Адміністратор може завантажити карту кооперативу.',
  },
  status: {
    RED: 'Борг понад поріг',
    YELLOW: 'Є борг',
    GREEN: 'Без боргу',
    GREY: 'Спільні землі',
  },
  errors: {
    generic: 'Не вдалося завантажити дані. Спробуйте ще раз.',
    retry: 'Повторити',
  },
};
