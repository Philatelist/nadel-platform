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
  nav: { cabinet: 'Кабінет', map: 'Карта' },
  me: { title: 'Мій кабінет', roles: 'Ролі', cooperative: 'Кооператив', noCooperative: 'Оператор платформи' },
  roles: {
    CHAIRMAN: 'Голова',
    TREASURER: 'Скарбник',
    ELECTRICIAN: 'Електрик',
    MEMBER: 'Член кооперативу',
    PLATFORM_OPERATOR: 'Оператор платформи',
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
